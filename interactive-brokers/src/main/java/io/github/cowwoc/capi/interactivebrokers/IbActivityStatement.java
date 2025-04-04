package io.github.cowwoc.capi.interactivebrokers;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

import static com.github.cowwoc.requirements10.java.DefaultJavaValidators.requireThat;
import static com.github.cowwoc.requirements10.java.DefaultJavaValidators.that;

/**
 * An activity statement.
 *
 * @param header                 information about the statement
 * @param account                information about the account
 * @param currencyToCashActivity cash activity for each currency held
 * @param trades                 the trades
 * @param forex                  the foreign currency exchanges
 * @param deposits               the deposits and withdrawals
 * @param dividends              the dividends paid and tax withheld
 */
public record IbActivityStatement(Header header, Account account,
                                  Map<String, CashActivity> currencyToCashActivity, List<Trade> trades,
                                  List<Forex> forex, List<Deposit> deposits, List<Dividend> dividends)
{
	private static final DateTimeFormatter HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy");
	private static final DateTimeFormatter HEADER_DATE_TIME_FORMAT = DateTimeFormatter.
		ofPattern("yyyy-MM-dd, HH:mm:ss z");
	private static final DateTimeFormatter LOCAL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter LOCAL_DATE_TIME_FORMAT = DateTimeFormatter.
		ofPattern("yyyy-MM-dd, HH:mm:ss");
	private static final CsvMapper CSV_MAPPER = new CsvMapper();
	private static final CsvSchema EMPTY_SCHEMA = CsvSchema.emptySchema();
	private static final CsvSchema SCHEMA_WITH_HEADER = EMPTY_SCHEMA.withHeader();
	/**
	 * The precision to use for numbers.
	 */
	private static final int PRECISION = 4;

	/**
	 * Loads a statement from a CSV file.
	 *
	 * @param csv the path of the CSV file
	 * @return the parsed statement
	 * @throws IOException if an I/O error occurs while reading the file
	 */
	public static IbActivityStatement load(Path csv) throws IOException
	{
		List<String> lines = Files.readAllLines(csv);
		String firstLine = lines.getFirst();
		if (firstLine.startsWith("\uFEFF"))
		{
			// Strip out the Byte Order Mark (BOM) at the beginning of the file indicating the use of UTF-8.
			lines.set(0, firstLine.substring(1));
		}

		Header header = parseHeader(filter(lines, line -> line.containsKey("Statement")));
		Account account = parseAccount(filter(lines, line -> line.containsKey("Account Information")));
		Map<String, CashActivity> cashActivities = parseCashActivities(filter(lines, line ->
			line.containsKey("Cash Report")));
		Map<String, Set<Code>> stringCodeToEnums = parseCodes(filter(lines, line -> line.containsKey("Codes")));
		Map<String, MarkToMarket> symbolToMarkToMarket = parseMarkToMarket(filter(lines,
			line -> line.containsKey("Mark-to-Market Performance Summary")));
		List<Trade> trades = parseTrades(filter(lines, line -> line.containsKey("Trades") &&
			!line.get("Asset Category").equals("Forex")), stringCodeToEnums, symbolToMarkToMarket);
		List<Forex> forex = parseForex(filter(lines, line -> line.containsKey("Trades") &&
			line.get("Asset Category").equals("Forex")));

		List<Deposit> deposits = parseDeposits(filter(lines, line ->
			line.containsKey("Deposits & Withdrawals")));
		List<Dividend> dividends = parseDividends(filter(lines, line ->
			line.containsKey("Dividends") || line.containsKey("Withholding Tax")));

		return new IbActivityStatement(header, account, cashActivities, trades, forex, deposits, dividends);
	}

	/**
	 * Filters the given {@code lines}, retaining only those that match {@code predicate}. The matching lines
	 * are then joined into a single string, separated by {@code \n}.
	 *
	 * @param lines     the file contents, represented as a collection of lines
	 * @param predicate a function that returns {@code true} if the given section matches the criteria
	 * @return one {@code Reader} per section that matches {@code predicate}
	 * @throws IOException if the line cannot be parsed as comma-separated values
	 */
	@SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
	private static List<Reader> filter(List<String> lines, Predicate<Map<String, String>> predicate)
		throws IOException
	{
		ObjectReader lineReader = CSV_MAPPER.readerForListOf(String.class).
			with(EMPTY_SCHEMA);
		StringJoiner joiner = new StringJoiner("\n");
		List<Reader> sections = new ArrayList<>();
		String firstColumn = "";

		for (String line : lines)
		{
			List<String> row = lineReader.readValue(line);
			if ((!row.isEmpty() && !row.getFirst().equals(firstColumn)) || row.contains("Header"))
			{
				// Start of a new section
				firstColumn = row.getFirst();
				String sectionAsString = joiner.toString();
				if (!sectionAsString.isEmpty())
				{
					try (StringReader section = new StringReader(sectionAsString);
					     MappingIterator<Map<String, String>> it = CSV_MAPPER.readerForMapOf(String.class).
						     with(SCHEMA_WITH_HEADER).
						     readValues(section))
					{
						if (it.hasNext() && predicate.test(it.next()))
							sections.add(new StringReader(sectionAsString));
					}
				}
				joiner = new StringJoiner("\n");
			}
			joiner.add(line);
		}
		return sections;
	}

	/**
	 * Parses the transaction codes.
	 *
	 * @param sections the code sections
	 * @return a map from the string representation of each code to its corresponding enum values
	 * @throws NullPointerException     if {@code sections} is null
	 * @throws IllegalArgumentException if:
	 *                                  <ul>
	 *                                    <li>there is more than one section.</li>
	 *                                    <li>the statement type is not "Activity Statement".</li>
	 *                                  </ul>
	 * @throws IOException              if an I/O error occurs while reading the lines
	 */
	private static Map<String, Set<Code>> parseCodes(List<Reader> sections) throws IOException
	{
		requireThat(sections, "sections").size().isEqualTo(1);

		Map<String, Set<Code>> stringCodeToEnums = new HashMap<>();
		try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
			with(SCHEMA_WITH_HEADER).
			readValues(sections.getFirst()))
		{
			while (it.hasNext())
			{
				Map<String, String> row = it.next();
				requireThat(row.get("Header"), "Header").isEqualTo("Data");
				String codeAsString = row.get("Code");
				String meaning = row.get("Meaning");
				Set<Code> codes = switch (meaning)
				{
					case "Assignment" -> Set.of(Code.ASSIGNMENT);
					case "Resulted from an Expired Position" -> Set.of(Code.EXPIRED);
					case "Opening Trade" -> Set.of(Code.OPEN);
					case "Closing Trade" -> Set.of(Code.CLOSE);
					case "Partial Execution" -> Set.of(Code.PARTIAL_EXECUTION);
					case "The transaction was executed against IB or an affiliate" -> Set.of(Code.INTERNAL_TRADE);
					case "A portion of the order was executed against IB or an affiliate; IB acted as agent on " +
						     "a portion." -> Set.of(Code.PARTIAL_EXECUTION, Code.INTERNAL_TRADE);
					case "The fractional portion of this trade was executed against IB or an affiliate. IB acted as " +
						     "agent for the whole share portion of this trade." -> Set.of(
						Code.FRACTIONAL_PORTION_TRADED_INTERNALLY);
					case "IB acted as agent for both the fractional share portion and the whole share portion of " +
						     "this trade; the fractional share portion was executed by an IB Affiliate as riskless " +
						     "principal." -> Set.of(Code.INTERNAL_TRADE);
					case "Ordered by IB (Margin Violation)" -> Set.of(Code.MARGIN_VIOLATION);
					// ignore unknown codes
					default -> Set.of();
				};
				if (!codes.isEmpty())
				{
					Set<Code> oldCodes = stringCodeToEnums.put(codeAsString, codes);
					assert oldCodes == null : "code has multiple meanings: " + codeAsString;
				}
			}
		}
		return stringCodeToEnums;
	}

	/**
	 * Parses the header.
	 *
	 * @param sections the header sections
	 * @return a {@code Header} object
	 * @throws NullPointerException     if {@code sections} is null
	 * @throws IllegalArgumentException if:
	 *                                  <ul>
	 *                                    <li>there is more than one section.</li>
	 *                                    <li>the statement type is not "Activity Statement".</li>
	 *                                  </ul>
	 * @throws IOException              if an I/O error occurs while reading the lines
	 */
	private static Header parseHeader(List<Reader> sections) throws IOException
	{
		requireThat(sections, "sections").size().isEqualTo(1);
		LocalDate startDate = null;
		LocalDate endDate = null;
		LocalDateTime generatedAt = null;

		try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
			with(SCHEMA_WITH_HEADER).
			readValues(sections.getFirst()))
		{
			while (it.hasNext())
			{
				Map<String, String> row = it.next();
				requireThat(row.get("Header"), "Header").isEqualTo("Data");
				String name = row.get("Field Name");
				String value = row.get("Field Value");
				switch (name)
				{
					case "BrokerName", "BrokerAddress" ->
					{
						// ignore
					}
					case "Title" -> requireThat(value, "value").withContext(row, "row").
						isEqualTo("Activity Statement");
					case "Period" ->
					{
						requireThat(startDate, "startDate").isNull();
						requireThat(endDate, "endDate").isNull();

						String[] period = value.split("-");
						startDate = LocalDate.parse(period[0].strip(), HEADER_DATE_FORMAT);
						endDate = LocalDate.parse(period[1].strip(), HEADER_DATE_FORMAT);
					}
					case "WhenGenerated" ->
					{
						requireThat(generatedAt, "generatedAt").isNull();
						generatedAt = LocalDateTime.parse(value, HEADER_DATE_TIME_FORMAT);
					}
					default -> throw new AssertionError("Unsupported name: " + row);
				}
			}
		}
		return new Header(startDate, endDate, generatedAt);
	}

	/**
	 * Parses the account information.
	 *
	 * @param sections the account sections
	 * @return an {@code Account} object
	 * @throws NullPointerException     if {@code sections} is null
	 * @throws IllegalArgumentException if there is more than one section
	 * @throws IOException              if an I/O error occurs while reading the lines
	 */
	private static Account parseAccount(List<Reader> sections) throws IOException
	{
		requireThat(sections, "sections").size().isEqualTo(1);
		String owner = null;
		String number = null;

		try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
			with(SCHEMA_WITH_HEADER).
			readValues(sections.getFirst()))
		{
			while (it.hasNext())
			{
				Map<String, String> row = it.next();
				requireThat(row.get("Header"), "Header").isEqualTo("Data");
				String name = row.get("Field Name");
				switch (name)
				{
					case "Name" ->
					{
						requireThat(owner, "owner").isNull();
						owner = row.get("Field Value");
					}
					case "Account" ->
					{
						requireThat(number, "number").isNull();
						number = row.get("Field Value");
					}
					case "Account Type", "Customer Type", "Account Capabilities", "Base Currency" ->
					{
						// ignore
					}
					default -> throw new AssertionError("Unsupported name: " + row);
				}
			}
		}
		return new Account(number, owner);
	}

	/**
	 * Parses the cash activities.
	 *
	 * @param sections one section per currency
	 * @return a map from a currency to its {@code CashActivity}
	 * @throws NullPointerException if {@code sections} is null
	 * @throws IOException          if an I/O error occurs while reading the lines
	 */
	@SuppressWarnings("PMD.NcssCount")
	private static Map<String, CashActivity> parseCashActivities(List<Reader> sections) throws IOException
	{
		Map<String, CashActivity> activities = new HashMap<>();
		Set<String> currencies = new HashSet<>();
		Map<String, BigDecimal> openingBalance = new HashMap<>();
		Map<String, BigDecimal> closingBalance = new HashMap<>();

		for (Reader section : sections)
		{
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					requireThat(row.get("Header"), "Header").isEqualTo("Data");

					String currency = row.get("Currency");
					if (currency.equals("Base Currency Summary"))
					{
						// The data will be repeated with the currency's name explicitly mentioned
						continue;
					}
					currencies.add(currency);

					String name = row.get("Currency Summary");
					String total = row.get("Total");
					switch (name)
					{
						case "Starting Cash" ->
						{
							BigDecimal previousValue = openingBalance.put(currency, new BigDecimal(total));
							if (previousValue != null)
								throw new AssertionError(currency + " already had an opening balance of " + previousValue);
						}
						case "Ending Cash" ->
						{
							BigDecimal previousValue = closingBalance.put(currency, new BigDecimal(total));
							if (previousValue != null)
								throw new AssertionError(currency + " already had an closing balance of " + previousValue);
						}
						case "Ending Settled Cash", "Deposits", "Trades (Sales)", "Trades (Purchase)", "Commissions",
						     "Dividends", "Payment In Lieu of Dividends", "Withholding Tax", "Account Transfers",
						     "Broker Interest Paid and Received" ->
						{
							// ignore
						}
						default -> throw new AssertionError("Unsupported Currency Summary: " + row);
					}
				}
			}
		}
		for (String currency : currencies)
		{
			activities.put(currency,
				new CashActivity(currency, openingBalance.get(currency), closingBalance.get(currency)));
		}
		return activities;
	}

	/**
	 * Parses the mark-to-market section, comparing the portfolio between the beginning and end of the
	 * statement's period.
	 *
	 * @param sections the mark-to-market sections
	 * @return a map from the symbol of each asset to its {@code MarkToMarket} value
	 * @throws NullPointerException     if {@code sections} is null
	 * @throws IllegalArgumentException if there is more than one section
	 * @throws IOException              if an I/O error occurs while reading the lines
	 */
	private static Map<String, MarkToMarket> parseMarkToMarket(List<Reader> sections) throws IOException
	{
		requireThat(sections, "sections").size().isEqualTo(1);
		Map<String, MarkToMarket> symbolToMarkToMarket = new HashMap<>();

		for (Reader section : sections)
		{
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					requireThat(row.get("Header"), "Header").isEqualTo("Data");

					String assetCategory = row.get("Asset Category");
					boolean skip = switch (assetCategory)
					{
						case "Stocks", "Equity and Index Options" -> false;
						case "Total", "Forex", "Total (All Assets)", "Broker Interest Paid and Received" -> true;
						default -> throw new AssertionError("Unsupported asset category: " + row);
					};
					if (skip)
						continue;
					String rawSymbol = row.get("Symbol");
					ParsedSymbol symbol = ParsedSymbol.fromStatement(rawSymbol);
					BigDecimal startQuantity = new BigDecimal(row.get("Prior Quantity").replaceAll(",", "")).
						setScale(PRECISION, RoundingMode.HALF_EVEN);
					BigDecimal endQuantity = new BigDecimal(row.get("Current Quantity").replaceAll(",", "")).
						setScale(PRECISION, RoundingMode.HALF_EVEN);
					symbolToMarkToMarket.put(symbol.value, new MarkToMarket(startQuantity, endQuantity));
				}
			}
		}
		return symbolToMarkToMarket;
	}

	/**
	 * Parses the trades.
	 *
	 * @param sections             one section per asset type (e.g. Stocks, Equity and Index Options)
	 * @param stringCodeToEnums    a map from the String representation of each code to its corresponding enum
	 *                             values
	 * @param symbolToMarkToMarket a map from the symbol of each asset to its {@code MarkToMarket} value
	 * @return a list of {@code Trade}s
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if an I/O error occurs while reading the lines
	 */
	@SuppressWarnings("PMD.NcssCount")
	private static List<Trade> parseTrades(List<Reader> sections, Map<String, Set<Code>> stringCodeToEnums,
		Map<String, MarkToMarket> symbolToMarkToMarket)
		throws IOException
	{
		requireThat(stringCodeToEnums, "stringCodeToEnums").isNotNull();
		List<Trade> trades = new ArrayList<>();

		// Design: Assets have a different ID per position, even if they have the same symbol.
		// This allows us to differentiate between different option contracts even if they have the same
		// underlying asset.
		Map<String, Integer> symbolToId = new HashMap<>();
		Map<Integer, BigDecimal> assetToTotalUnits = new HashMap<>();
		int nextId = 0;

		// Adds assets that were held in the previous statement.
		for (Entry<String, MarkToMarket> entry : symbolToMarkToMarket.entrySet())
		{
			String symbol = entry.getKey();
			Integer assetId = nextId++;
			assetToTotalUnits.put(assetId, entry.getValue().startQuantity());
			symbolToId.put(symbol, assetId);
		}

		Logger log = LoggerFactory.getLogger(IbActivityStatement.class);
		for (Reader section : sections)
		{
			Set<String> symbolsReferencedBySection = new HashSet<>();
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					log.debug("row: {}", row);
					boolean skip = switch (row.get("Header"))
					{
						case "Data" -> false;
						case "SubTotal", "Total" -> true;
						default -> throw new AssertionError("Unsupported header: " + row);
					};
					if (skip)
						continue;

					String codesAsString = row.get("Code");
					Set<Code> codes = EnumSet.noneOf(Code.class);
					for (String codeAsString : codesAsString.split(";"))
					{
						Set<Code> codesEntry = stringCodeToEnums.get(codeAsString);
						if (codesEntry == null)
							throw new IOException("Unknown code: " + codeAsString);
						codes.addAll(codesEntry);
					}

					// INTERNAL_TRADE implies more than FRACTIONAL_PORTION_TRADED_INTERNALLY but some trades are
					// annotated with both codes.
					if (codes.contains(Code.INTERNAL_TRADE))
						codes.remove(Code.FRACTIONAL_PORTION_TRADED_INTERNALLY);

					String assetCategory = row.get("Asset Category");
					ParsedSymbol symbol = switch (assetCategory)
					{
						case "Stocks", "Equity and Index Options" ->
						{
							String rawSymbol = row.get("Symbol");
							yield ParsedSymbol.fromStatement(rawSymbol);
						}
						default -> throw new AssertionError("Unsupported asset category: " + row);
					};

					LocalDateTime dateTime = LocalDateTime.parse(row.get("Date/Time"), LOCAL_DATE_TIME_FORMAT);
					BigDecimal quantity = new BigDecimal(row.get("Quantity").replaceAll(",", "")).
						setScale(PRECISION, RoundingMode.HALF_EVEN);

					BigDecimal price = new BigDecimal(row.get("T. Price")).setScale(PRECISION, RoundingMode.HALF_EVEN);
					BigDecimal proceeds = new BigDecimal(row.get("Proceeds")).setScale(PRECISION,
						RoundingMode.HALF_EVEN);
					BigDecimal commission = new BigDecimal(row.get("Comm/Fee")).setScale(PRECISION,
						RoundingMode.HALF_EVEN);
					String currency = row.get("Currency");
					Integer assetId = symbolToId.get(symbol.value);
					symbolsReferencedBySection.add(symbol.value);

					BigDecimal oldTotalUnits = assetToTotalUnits.getOrDefault(assetId, BigDecimal.ZERO);
					assert !quantity.equals(BigDecimal.ZERO) : row;

					BigDecimal newTotalUnits = oldTotalUnits.add(quantity);
					if (oldTotalUnits.signum() == -newTotalUnits.signum())
					{
						// Split the trade into two since it involves a combination of:
						// 1. Buying to close a short position followed by a long buy, or
						// 2. Selling to close a long position followed by a short sell.
						assert assetId != null : "The asset being closed is unknown: " + symbol;

						BigDecimal proportionOfClose = oldTotalUnits.abs().divide(quantity.abs(), RoundingMode.HALF_EVEN);
						BigDecimal commissionForClose = proportionOfClose.multiply(commission);
						BigDecimal proceedsForClose = proportionOfClose.multiply(proceeds);
						Set<Code> codesForClose = EnumSet.copyOf(codes);
						codesForClose.remove(Code.OPEN);

						// The first trade closes the position
						trades.add(new Trade(dateTime, symbol.value, assetId, oldTotalUnits.negate(), price,
							proceedsForClose, commissionForClose, currency, codesForClose, symbol.underlyingAsset,
							symbol.strikePrice));

						// The second trade opens a new position
						assetId = nextId++;
						symbolToId.put(symbol.value, assetId);
						BigDecimal commissionForOpen = commission.subtract(commissionForClose);
						BigDecimal proceedsForOpen = proceeds.subtract(proceedsForClose);
						Set<Code> codesForOpen = EnumSet.copyOf(codes);
						codesForClose.remove(Code.CLOSE);

						assetToTotalUnits.put(assetId, newTotalUnits);
						trades.add(new Trade(dateTime, symbol.value, assetId, newTotalUnits, price, proceedsForOpen,
							commissionForOpen, currency, codesForOpen, symbol.underlyingAsset, symbol.strikePrice));
					}
					else
					{
						boolean closedPosition = newTotalUnits.compareTo(BigDecimal.ZERO) == 0;
						if (closedPosition)
						{
							assert assetId != null : "The asset being closed is unknown: " + symbol;
							assetToTotalUnits.remove(assetId);
							// Ensure that the next trade of this asset receives a new ID
							symbolToId.remove(symbol.value);
						}
						else
						{
							if (assetId == null)
							{
								// The opening of a new trading position
								assetId = nextId++;
								symbolToId.put(symbol.value, assetId);
							}
							assetToTotalUnits.put(assetId, newTotalUnits);
						}
						trades.add(new Trade(dateTime, symbol.value, assetId, quantity, price, proceeds, commission,
							currency, codes, symbol.underlyingAsset, symbol.strikePrice));
					}
				}
			}
			// Ensure that symbols get assigned a different ID per section but only reset the symbols that were
			// referenced; otherwise, we might remove assets that were held in the previous statement before they
			// get referenced.
			for (String symbol : symbolsReferencedBySection)
				symbolToId.remove(symbol);
		}
		return trades;
	}

	/**
	 * Parses the foreign currency exchanges.
	 *
	 * @param sections one section per currency pair
	 * @return a list of {@code Forex}s
	 * @throws NullPointerException if {@code sections} is null
	 * @throws IOException          if an I/O error occurs while reading the lines
	 */
	private static List<Forex> parseForex(List<Reader> sections) throws IOException
	{
		List<Forex> exchanges = new ArrayList<>();

		for (Reader section : sections)
		{
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					boolean skip = switch (row.get("Header"))
					{
						case "Data" -> false;
						case "SubTotal", "Total" -> true;
						default -> throw new AssertionError("Unsupported header: " + row);
					};
					if (skip)
						continue;

					String symbol = row.get("Symbol");
					String[] currencyPair = symbol.split("\\.");
					requireThat(currencyPair, "currencyPair").length().isEqualTo(2);
					LocalDateTime dateTime = LocalDateTime.parse(row.get("Date/Time"), LOCAL_DATE_TIME_FORMAT);
					BigDecimal quantity = new BigDecimal(row.get("Quantity").replaceAll(",", ""));
					BigDecimal price = new BigDecimal(row.get("T. Price"));
					BigDecimal proceeds = new BigDecimal(row.get("Proceeds"));
					BigDecimal commission = new BigDecimal(row.get("Comm in USD"));
					exchanges.add(new Forex(dateTime, currencyPair[1], currencyPair[0], quantity, price, proceeds,
						commission));
				}
			}
		}
		return exchanges;
	}

	/**
	 * Parses deposits and withdrawals.
	 *
	 * @param sections one section per currency
	 * @return a list of {@code Deposit}s
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if an I/O error occurs while reading the lines
	 */
	private static List<Deposit> parseDeposits(List<Reader> sections) throws IOException
	{
		List<Deposit> deposits = new ArrayList<>();

		for (Reader section : sections)
		{
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					requireThat(row.get("Header"), "Header").isEqualTo("Data");

					String currency = row.get("Currency");
					if (currency.startsWith("Total"))
						continue;
					LocalDate date = LocalDate.parse(row.get("Settle Date"), LOCAL_DATE_FORMAT);
					BigDecimal quantity = new BigDecimal(row.get("Amount"));
					String description = row.get("Description");
					deposits.add(new Deposit(date, currency, quantity, description));
				}
			}
		}
		return deposits;
	}

	/**
	 * Parses dividend paid out and tax withheld.
	 *
	 * @param sections one section per currency
	 * @return a list of {@code Dividend}s
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if an I/O error occurs while reading the lines
	 */
	private static List<Dividend> parseDividends(List<Reader> sections) throws IOException
	{
		List<Dividend> dividends = new ArrayList<>();

		for (Reader section : sections)
		{
			try (MappingIterator<Map<String, String>> it = CSV_MAPPER.readerFor(Map.class).
				with(SCHEMA_WITH_HEADER).
				readValues(section))
			{
				while (it.hasNext())
				{
					Map<String, String> row = it.next();
					requireThat(row.get("Header"), "Header").isEqualTo("Data");

					String currency = row.get("Currency");
					if (currency.startsWith("Total"))
						continue;
					LocalDate date = LocalDate.parse(row.get("Date"), LOCAL_DATE_FORMAT);
					BigDecimal quantity = new BigDecimal(row.get("Amount"));
					String description = row.get("Description");
					dividends.add(new Dividend(date, currency, quantity, description));
				}
			}
		}
		return dividends;
	}

	/**
	 * Creates a new instance.
	 *
	 * @param header                 information about the statement
	 * @param account                information about the account
	 * @param currencyToCashActivity maps each currency to its cash activity
	 * @param trades                 the trades
	 * @param forex                  the foreign currency exchanges
	 * @param deposits               the deposits and withdrawals
	 * @param dividends              the dividends paid and tax withheld
	 * @throws NullPointerException if any of the arguments are null
	 */
	public IbActivityStatement
	{
		requireThat(header, "header").isNotNull();
		requireThat(account, "account").isNotNull();
		requireThat(currencyToCashActivity, "currencyToCashActivity").isNotNull();
		requireThat(trades, "trades").isNotNull();
		requireThat(forex, "forex").isNotNull();
		requireThat(deposits, "deposits").isNotNull();
		requireThat(dividends, "dividends").isNotNull();
	}

	/**
	 * The statement's header.
	 *
	 * @param startDate   the first day included in the statement
	 * @param endDate     the last day included in the statement (inclusive)
	 * @param generatedAt the time that the statement was generated
	 */
	public record Header(LocalDate startDate, LocalDate endDate, LocalDateTime generatedAt)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param startDate   the first day included in the statement
		 * @param endDate     the last day included in the statement
		 * @param generatedAt the date that the statement was generated
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if:
		 *                                  <ul>
		 *                                    <li>{@code startDate} is greater than {@code endDate}.</li>
		 *                                    <li>{@code generatedAt} is less than {@code endDate}.</li>
		 *                                  </ul>
		 */
		public Header
		{
			requireThat(startDate, "startDate").isNotNull();
			requireThat(endDate, "endDate").isNotNull().isGreaterThanOrEqualTo(startDate, "startDate");
			requireThat(generatedAt.toLocalDate(), "generatedAt").isNotNull().
				isGreaterThanOrEqualTo(endDate, "endDate");
		}
	}

	/**
	 * The account's information.
	 *
	 * @param number the account number
	 * @param owner  the name of the account owner
	 */
	public record Account(String number, String owner)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param number the account number
		 * @param owner  the name of the account owner
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if any of the arguments contain leading or trailing whitespace or are
		 *                                  empty
		 */
		public Account
		{
			requireThat(number, "number").isStripped().isNotEmpty();
			requireThat(owner, "owner").isStripped().isNotEmpty();
		}
	}

	/**
	 * Information about a currency-specific cash activity.
	 *
	 * @param currency       the currency of the cash
	 * @param openingBalance the starting cash balance, which may be negative if the investor owes money
	 * @param closingBalance the end cash balance, which may be negative if the investor owes money
	 */
	public record CashActivity(String currency, BigDecimal openingBalance, BigDecimal closingBalance)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param currency       the currency of the cash
		 * @param openingBalance the starting cash balance, which may be negative if the investor owes money
		 * @param closingBalance the end cash balance, which may be negative if the investor owes money
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if any of the arguments contain leading or trailing whitespace or are
		 *                                  empty
		 */
		public CashActivity
		{
			requireThat(currency, "currency").isStripped().isNotEmpty();
			requireThat(openingBalance, "openingBalance").isNotNull();
			requireThat(closingBalance, "closingBalance").isNotNull();
		}
	}

	/**
	 * A trade of assets.
	 *
	 * @param startQuantity the total units held at the start of the statement's period
	 * @param endQuantity   the total units held at the end of the statement's period
	 */
	public record MarkToMarket(BigDecimal startQuantity, BigDecimal endQuantity)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param startQuantity the total units held at the start of the statement's period
		 * @param endQuantity   the total units held at the end of the statement's period
		 * @throws NullPointerException if any of the arguments are null
		 */
		public MarkToMarket
		{
			requireThat(startQuantity, "startQuantity").isNotNull();
			requireThat(endQuantity, "endQuantity").isNotNull();
		}
	}

	/**
	 * A parsed representation of an asset's symbol.
	 *
	 * @param value           the value of the symbol
	 * @param underlyingAsset (optional) the symbol of the underlying asset if this asset is an option;
	 *                        otherwise, undefined.
	 * @param strikePrice     (optional) the strike price if this asset is an option; otherwise, undefined.
	 */
	private record ParsedSymbol(String value, String underlyingAsset, BigDecimal strikePrice)
	{
		/**
		 * Parses the String representation of an asset symbol in the format used by the Activity statement.
		 *
		 * @param symbol the String representation
		 * @return the parsed symbol
		 */
		public static ParsedSymbol fromStatement(String symbol)
		{
			// e.g. SQQQ 17JUN22 42.0 P
			String[] tokens = symbol.split(" ");
			if (tokens.length == 1)
				return new ParsedSymbol(tokens[0], "", BigDecimal.ZERO);
			assert that(tokens, "tokens").length().isEqualTo(4).elseThrow();
			String underlyingAsset = tokens[0];
			String date = tokens[1];
			BigDecimal strikePrice = new BigDecimal(tokens[2]).setScale(PRECISION, RoundingMode.HALF_EVEN);
			String type = switch (tokens[3])
			{
				case "C" -> "CALL";
				case "P" -> "PUT";
				default -> throw new AssertionError("Unsupported option type: " + tokens[4]);
			};
			// e.g. PUT SQQQ 17JUN22@42.0
			String value = type + " " + underlyingAsset + " " + date + "@" + strikePrice.toPlainString();
			return new ParsedSymbol(value, underlyingAsset, strikePrice);
		}

		/**
		 * Creates a new instance.
		 *
		 * @param value           the value of the symbol
		 * @param underlyingAsset (optional) the symbol of the underlying asset if this asset is an option;
		 *                        otherwise, undefined.
		 * @param strikePrice     (optional) the strike price if this asset is an option; otherwise, undefined.
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if:
		 *                                  <ul>
		 *                                  <li>any of the arguments contain leading or trailing whitespace.</li>
		 *                                  <li>any of the mandatory arguments are empty.</li>
		 *                                  <li>{@code strikePrice} is negative.</li>
		 *                                  </ul>
		 */
		private ParsedSymbol
		{
			requireThat(value, "value").isStripped().isNotEmpty();
			requireThat(underlyingAsset, "underlyingAsset").isStripped();
			requireThat(strikePrice, "strikePrice").isNotNegative();
		}
	}

	/**
	 * A trade of assets.
	 *
	 * @param dateTime        the date and time of the trade
	 * @param symbol          the symbol of the asset
	 * @param assetId         a value that groups trades related to the same stock symbol, equity, or index
	 *                        option, helping to identify and differentiate transactions within the same
	 *                        category
	 * @param quantity        the quantity being traded; negative if securities are being sold, positive if they
	 *                        are being bought.
	 * @param price           the price of each unit
	 * @param proceeds        The total amount received from the trade, which is negative if the trade resulted
	 *                        in a cost
	 * @param commission      the trade fees, typically represented as a negative value to indicate a cost. It
	 *                        may be positive in the case of liquidity rebates, exchange incentives, or in the
	 *                        case of an accounting correction.
	 * @param currency        the currency of all quantities
	 * @param codes           annotations that provide additional information about the trade
	 * @param underlyingAsset The symbol of the underlying asset if this asset is an option; otherwise,
	 *                        undefined.
	 * @param strikePrice     The strike price if this asset is an option; otherwise, undefined.
	 */
	public record Trade(LocalDateTime dateTime, String symbol, int assetId, BigDecimal quantity,
	                    BigDecimal price, BigDecimal proceeds, BigDecimal commission, String currency,
	                    Set<Code> codes, String underlyingAsset, BigDecimal strikePrice)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param dateTime        the date and time of the trade
		 * @param symbol          the symbol of the asset
		 * @param quantity        the quantity being traded; negative if securities are being sold, positive if
		 *                        they are being bought.
		 * @param assetId         a value that groups trades related to the same stock symbol, equity, or index
		 *                        option, helping to identify and differentiate transactions within the same
		 *                        category
		 * @param price           the price of each unit
		 * @param proceeds        The total amount received from the trade, which is negative if the trade
		 *                        resulted in a cost
		 * @param commission      the trade fees, typically represented as a negative value to indicate a cost. It
		 *                        may be positive in the case of liquidity rebates, exchange incentives, or in the
		 *                        case of an accounting correction.
		 * @param currency        the currency of all quantities
		 * @param codes           annotations that provide additional information about the trade
		 * @param underlyingAsset (optional) the symbol of the underlying asset if this asset is an option;
		 *                        otherwise, undefined.
		 * @param strikePrice     (optional) the strike price if this asset is an option; otherwise, undefined.
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if:
		 *                                  <ul>
		 *                                  <li>any of the arguments contain leading or trailing whitespace.</li>
		 *                                  <li>any of the mandatory arguments are empty.</li>
		 *                                  <li>{@code assetId}, {@code price} or {@code strikePrice} are
		 *                                  negative.</li>
		 *                                  </ul>
		 */
		public Trade
		{
			requireThat(dateTime, "dateTime").isNotNull();
			requireThat(symbol, "symbol").isStripped().isNotEmpty();
			requireThat(assetId, "assetId").isNotNegative();
			requireThat(quantity, "quantity").isNotNull();
			requireThat(price, "price").isNotNegative();
			requireThat(proceeds, "proceeds").isNotNull();
			requireThat(commission, "commission").isNotNull();
			requireThat(currency, "currency").isNotNull();
			requireThat(codes, "codes").isNotNull();
			requireThat(underlyingAsset, "underlyingAsset").isStripped();
			requireThat(strikePrice, "strikePrice").isNotNegative();
		}
	}

	/**
	 * A foreign currency trade.
	 *
	 * @param dateTime       the date and time of the trade
	 * @param sourceCurrency the source being spent
	 * @param targetCurrency the target being received
	 * @param quantity       the quantity being traded; negative if securities are being sold, positive if they
	 *                       are being bought.
	 * @param price          the price of each unit
	 * @param proceeds       The total amount received from the trade, which is negative if the trade resulted
	 *                       in a cost
	 * @param commission     the trade fees in USD, typically represented as a negative value to indicate a
	 *                       cost. It may be positive in the case of liquidity rebates, exchange incentives, or
	 *                       in the case of an accounting correction.
	 */
	public record Forex(LocalDateTime dateTime, String sourceCurrency, String targetCurrency,
	                    BigDecimal quantity, BigDecimal price, BigDecimal proceeds, BigDecimal commission)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param dateTime       the date and time of the trade
		 * @param sourceCurrency the source currency
		 * @param targetCurrency the target currency
		 * @param quantity       the quantity being traded; negative if {@code sourceCurrency} is being sold,
		 *                       positive if it is being bought.
		 * @param price          the price of each unit
		 * @param proceeds       The total amount received from the trade, which is negative if the trade resulted
		 *                       in a cost
		 * @param commission     the trade fees in USD, typically represented as a negative value to indicate a
		 *                       cost. It may be positive in the case of liquidity rebates, exchange incentives,
		 *                       or in the case of an accounting correction.
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if:
		 *                                  <ul>
		 *                                  <li>any of the arguments contain leading or trailing whitespace or
		 *                                  are empty.</li>
		 *                                  <li>{@code price} is negative.</li>
		 *                                  </ul>
		 */
		public Forex
		{
			requireThat(dateTime, "dateTime").isNotNull();
			requireThat(sourceCurrency, "sourceCurrency").isStripped().isNotEmpty();
			requireThat(targetCurrency, "targetCurrency").isStripped().isNotEmpty();
			requireThat(quantity, "quantity").isNotNull();
			requireThat(price, "price").isNotNegative();
			requireThat(proceeds, "proceeds").isNotNull();
			requireThat(commission, "commission").isNotNull();
		}
	}

	/**
	 * A deposit of funds into or withdrawal of funds out of an account.
	 * <p>
	 * All transfers are settled at the end of the business day.
	 *
	 * @param date        the settlement date
	 * @param currency    the type of currency that was transferred
	 * @param quantity    The quantity that was transferred; negative if the currency was withdrawn, positive if
	 *                    it was deposited.
	 * @param description a description of the transfer
	 */
	public record Deposit(LocalDate date, String currency, BigDecimal quantity, String description)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param date        the settlement date
		 * @param currency    the type of currency that was transferred
		 * @param quantity    The quantity that was transferred; negative if the currency was withdrawn, positive
		 *                    if it was deposited.
		 * @param description a description of the transfer
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if any of the arguments contain leading or trailing whitespace or are
		 *                                  empty
		 */
		public Deposit
		{
			requireThat(date, "date").isNotNull();
			requireThat(currency, "currency").isStripped().isNotEmpty();
		}
	}

	/**
	 * Represents a cash dividend or withholding of tax for a stock that pays out dividends.
	 * <p>
	 * All transfers are settled at the end of the business day.
	 *
	 * @param date        the settlement date
	 * @param currency    the type of currency that was transferred
	 * @param quantity    The quantity that was transferred; negative if the currency was withdrawn, positive if
	 *                    it was deposited.
	 * @param description a description of the transfer
	 */
	public record Dividend(LocalDate date, String currency, BigDecimal quantity, String description)
	{
		/**
		 * Creates a new instance.
		 *
		 * @param date        the settlement date
		 * @param currency    the type of currency that was transferred
		 * @param quantity    The quantity that was transferred; negative if the currency was withdrawn, positive
		 *                    if it was deposited.
		 * @param description a description of the transfer
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if any of the arguments contain leading or trailing whitespace or are
		 *                                  empty
		 */
		public Dividend
		{
			requireThat(date, "date").isNotNull();
			requireThat(currency, "currency").isStripped().isNotEmpty();
		}
	}

	/**
	 * Annotations that provide additional information about a trade.
	 */
	public enum Code
	{
		/**
		 * Indicates that the trade resulted from the assignment of an option contract.
		 * <p>
		 * This occurs when an option holder exercises their contract, requiring the seller (writer) to fulfill
		 * the terms. As a result:
		 *
		 * <ul>
		 *   <li>A short call assignment results in a trade that sells the underlying at the strike price.</li>
		 *   <li>A short put assignment results in a trade that buys the underlying at the strike price.</li>
		 * </ul>
		 */
		ASSIGNMENT,
		/**
		 * Indicates that an option was sold due to an expired contract.
		 */
		EXPIRED,
		/**
		 * Indicates the acquisition of an asset. This does not necessarily denote the initial acquisition of that
		 * asset.
		 */
		OPEN,
		/**
		 * Indicates the sale, transfer, or settlement of an asset. This does not necessarily signal the ending
		 * ownership of that asset.
		 */
		CLOSE,
		/**
		 * Indicates that the trade represents a partial fulfillment of a requested order. The remaining portion
		 * is either pending or unfulfilled.
		 */
		PARTIAL_EXECUTION,
		/**
		 * Indicates that the entire trade was executed internally against Interactive Brokers (IB) or one of its
		 * affiliated entities, rather than with a counterparty on the market.
		 */
		INTERNAL_TRADE,
		/**
		 * Indicates that the fractional portion of this trade was executed internally.
		 *
		 * @see #INTERNAL_TRADE
		 */
		FRACTIONAL_PORTION_TRADED_INTERNALLY,
		/**
		 * Indicates that an asset was sold due to a margin violation.
		 * <p>
		 * A margin violation occurs when the value of the trader's account falls below the required margin level,
		 * typically as a result of losses on existing positions. In such cases, the broker may sell assets in the
		 * account to restore the margin balance and protect against further risk exposure.
		 */
		MARGIN_VIOLATION
	}
}