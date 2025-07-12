package io.github.cowwoc.capi.rbc;

import io.github.cowwoc.capi.core.Browser;
import io.github.cowwoc.capi.core.Download;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.cowwoc.requirements12.java.DefaultJavaValidators.requireThat;

/**
 * Utilities for the <a href="https://www.rbcroyalbank.com/">Royal Bank website</a>.
 */
public final class Rbc
{
	private static final By ACCOUNTS_PANEL = By.cssSelector("#rbc-accounts-panel");
	private static final By ACCOUNT_NUMBER = By.cssSelector(".accounts-table__account-number");
	private static final By ACCOUNT_LINK = By.xpath("preceding-sibling::a[1]");
	private static final By VIEW_STATEMENTS = By.cssSelector(".no-print a[data-testid*=\"ViewStatement\"]");
	private static final By YEAR_SELECTOR = By.cssSelector("#rbc-select-3");
	private static final By SHOW_DOCUMENTS = By.cssSelector(".rbc-btn-wrapper-left span");
	private static final By PAGE_LOADING = By.cssSelector(".rbc-spinner-container");
	private static final By STATEMENT = By.cssSelector("tbody > tr");
	private static final By STATEMENT_DATE = By.cssSelector("td:nth-child(2) > span > span");
	private static final By STATEMENT_LINK = By.cssSelector("span > div > a");
	private static final By BACK_TO_ACCOUNT_NUMBERS = By.cssSelector("div.ficGlobalNav-secondaryNav > div > " +
		"ul > li.isActive > a");
	// Example: April 19, 2025
	private static final Pattern MONTH_AND_YEAR = Pattern.compile("^(\\w+)\\.?\\s+\\w+,\\s+(\\w+)");
	private final Browser browser;
	private final WebDriver client;
	private final Logger log = LoggerFactory.getLogger(Rbc.class);

	/**
	 * Creates a new instance.
	 *
	 * @param browser a browser whose active tab is already logged into the website
	 * @throws NullPointerException if {@code browser} is null
	 */
	public Rbc(Browser browser)
	{
		requireThat(browser, "browser").isNotNull();
		this.browser = browser;
		this.client = browser.getWebDriver();
	}

	/**
	 * Downloads the account statements that match the include filters.
	 *
	 * @param accountNumber a predicate that determines whether to download an account
	 * @param statements    a predicate that determines whether to download statements
	 * @return the downloaded statements
	 * @throws NullPointerException if any of the arguments are null
	 */
	public List<RbcAccountStatement> download(Predicate<String> accountNumber,
		Predicate<RbcStatementMetadata> statements)
	{
		Set<String> processed = new HashSet<>();
		try
		{
			List<RbcAccountStatement> downloads = new ArrayList<>();
			while (true)
			{
				WebElement accountNumberElement = null;
				// Elements must be reloaded each time because RBC won't let us open a new tab and the old elements
				// become stale.
				log.info("Looking up account numbers");
				for (WebElement candidate : client.findElements(ACCOUNT_NUMBER))
				{
					String accountNumberText = candidate.getText();
					if (!processed.add(accountNumberText))
						continue;
					if (accountNumber.test(accountNumberText))
					{
						accountNumberElement = candidate;
						break;
					}
				}
				if (accountNumberElement == null)
					break;
				String accountNumberText = accountNumberElement.getText();
				log.info("Stepping into account: {}", accountNumberText);
				WebElement accountLink = accountNumberElement.findElement(ACCOUNT_LINK);
				browser.click(accountLink);
				downloads.addAll(downloadStatements(accountNumberText, statements));
				log.info("Stepping back");
				WebElement backButton = client.findElement(BACK_TO_ACCOUNT_NUMBERS);
				browser.click(backButton);
				WebDriverWait wait = new WebDriverWait(client, Duration.ofMinutes(1));
				wait.until(_ -> browser.elementExists(ACCOUNTS_PANEL));
			}
			return downloads;
		}
		catch (NoSuchElementException e)
		{
			String dom = (String) ((JavascriptExecutor) client).
				executeScript("return document.documentElement.outerHTML;");
			log.debug("Page source: {}", dom);
			throw e;
		}
	}

	/**
	 * Downloads an account's statements.
	 *
	 * @param accountNumber the account number
	 * @param statements    a predicate that determines whether to download a statement
	 * @return the downloaded statements
	 * @throws NullPointerException if any of the arguments are null
	 */
	private List<RbcAccountStatement> downloadStatements(String accountNumber,
		Predicate<RbcStatementMetadata> statements)
	{
		WebElement viewStatementsLink = client.findElement(VIEW_STATEMENTS);
		browser.click(viewStatementsLink);

		WebDriverWait wait = new WebDriverWait(client, Duration.ofMinutes(1));
		wait.until(_ -> browser.elementExists(PAGE_LOADING));
		wait.until(_ -> !browser.elementExists(PAGE_LOADING));

		WebElement yearSelectorElement = client.findElement(YEAR_SELECTOR);
		Select yearSelect = new Select(yearSelectorElement);
		int numberOfOptions = yearSelect.getOptions().size();
		List<RbcAccountStatement> downloads = new ArrayList<>();
		assert yearSelect.getOptions().getFirst().getText().equals("Select");
		for (int i = 1; i < numberOfOptions; ++i)
		{
			WebElement desiredOption = yearSelect.getOptions().get(i);
			WebElement selectedOption = yearSelect.getFirstSelectedOption();
			log.info("Selecting " + desiredOption.getText());
			if (!desiredOption.equals(selectedOption))
			{
				yearSelect.selectByIndex(i);
				WebElement showDocuments = client.findElement(SHOW_DOCUMENTS);
				browser.click(showDocuments);

				wait.until(_ -> browser.elementExists(PAGE_LOADING));
				wait.until(_ -> !browser.elementExists(PAGE_LOADING));
			}

			for (WebElement statement : client.findElements(STATEMENT))
			{
				String date = statement.findElement(STATEMENT_DATE).getText();
				Matcher matcher = MONTH_AND_YEAR.matcher(date);
				boolean matchFound = matcher.find();
				if (matchFound)
				{
					String name = matcher.group(1).toUpperCase(Locale.ROOT);
					Month month = switch (name)
					{
						case "JAN" -> Month.JANUARY;
						case "FEB" -> Month.FEBRUARY;
						case "AUG" -> Month.AUGUST;
						case "SEPT" -> Month.SEPTEMBER;
						case "NOV" -> Month.NOVEMBER;
						case "OCT" -> Month.OCTOBER;
						case "DEC" -> Month.DECEMBER;
						default -> Month.valueOf(name);
					};
					int year = Integer.parseInt(matcher.group(2));
					LocalDate firstDay = LocalDate.of(year, month, 1);
					if (statements.test(new RbcStatementMetadata(accountNumber, firstDay)))
					{
						Instant now = Instant.now();
						WebElement statementLink = statement.findElement(STATEMENT_LINK);
						browser.click(statementLink);
						Download download = browser.waitForDownload(now);
						downloads.add(new RbcAccountStatement(download.target(), accountNumber, firstDay));
					}
				}
			}
		}
		return downloads;
	}
}