package io.github.cowwoc.capi.cibc;

import io.github.cowwoc.capi.core.Browser;
import io.github.cowwoc.capi.core.Download;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Utilities for the <a href="https://www.cibc.com/">CIBC website</a>.
 */
public final class Cibc
{
	private static final By MY_DOCUMENTS = By.cssSelector("a[aria-label^=\"My Documents\"]");
	private static final By ACCOUNT_STATEMENTS = By.xpath(
		"//a[.//span[contains(text(),'Account statements')]]");
	private static final By ACCOUNT_NUMBER = By.cssSelector("span.account-info span:nth-of-type(2)");
	private static final By BACK_TO_ACCOUNT_NUMBERS = By.cssSelector(".go-back");
	private static final By COLLAPSED_YEARS = By.cssSelector("div.ui-collapsible-pane.ui-collapsed h3 button");
	private static final By STATEMENT_DATE = By.cssSelector("ul.statement-list div.tile-content span");
	// Example: April 19, 2025
	private static final Pattern MONTH_AND_YEAR = Pattern.compile(
		"^(\\w+)\\s+\\w+,\\s+(\\w+)");
	// Example: March 20 to April 19, 2025
	private static final Pattern MONTH_DAY_TO_MONTH_DAY_YEAR = Pattern.compile(
		"^(\\w+)\\s\\w+\\s+\\w+\\s+\\w+,\\s+(\\w+)");
	// Example: March 1 to 30, 2025
	private static final Pattern MONTH_DAY_TO_DAY_YEAR = Pattern.compile(
		"\\w+\\s\\w+\\s+\\w+\\s+(\\w+)\\s+\\w+,\\s+(\\w+)");
	private final Browser browser;
	private final WebDriver client;
	private final Logger log = LoggerFactory.getLogger(Cibc.class);

	/**
	 * Creates a new instance.
	 *
	 * @param browser a browser whose active tab is already logged into the website
	 * @throws NullPointerException if {@code browser} is null
	 */
	public Cibc(Browser browser)
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
	public List<CibcAccountStatement> download(Predicate<String> accountNumber,
		Predicate<CibcStatementMetadata> statements)
	{
		Set<String> processed = new HashSet<>();
		try
		{
			WebElement myDocuments = client.findElement(MY_DOCUMENTS);
			browser.clickToOpenWindow(myDocuments);

			WebElement accountStatements = client.findElement(ACCOUNT_STATEMENTS);
			accountStatements.click();

			List<CibcAccountStatement> downloads = new ArrayList<>();
			while (true)
			{
				WebElement accountNumberElement = null;
				// Elements must be reloaded each time because CIBC won't let us open a new tab and the old elements
				// become stale.
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
				log.info("Stepping into account number: {}", accountNumberText);
				accountNumberElement.click();
				downloads.addAll(downloadStatements(accountNumberText, statements));
				log.info("Stepping back");
				WebElement backButton = client.findElement(BACK_TO_ACCOUNT_NUMBERS);
				backButton.click();
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
	private List<CibcAccountStatement> downloadStatements(String accountNumber,
		Predicate<CibcStatementMetadata> statements)
	{
		// Expand all years
		for (WebElement collapsedYear : client.findElements(COLLAPSED_YEARS))
			collapsedYear.click();

		List<CibcAccountStatement> downloads = new ArrayList<>();
		for (WebElement statement : client.findElements(STATEMENT_DATE))
		{
			String text = statement.getText();
			Matcher matcher = MONTH_AND_YEAR.matcher(text);
			boolean matchFound = matcher.find();
			if (!matchFound)
			{
				matcher = MONTH_DAY_TO_MONTH_DAY_YEAR.matcher(text);
				matchFound = matcher.find();
			}
			if (!matchFound)
			{
				matcher = MONTH_DAY_TO_DAY_YEAR.matcher(text);
				matchFound = matcher.find();
			}
			if (matchFound)
			{
				int month = Month.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)).getValue();
				int year = Integer.parseInt(matcher.group(2));
				LocalDate firstDay = LocalDate.of(year, month, 1);
				if (statements.test(new CibcStatementMetadata(accountNumber, firstDay)))
				{
					Instant now = Instant.now();
					browser.click(statement);
					Download download = browser.waitForDownload(now);
					downloads.add(new CibcAccountStatement(download.target(), accountNumber, firstDay));
				}
			}
		}
		return downloads;
	}
}