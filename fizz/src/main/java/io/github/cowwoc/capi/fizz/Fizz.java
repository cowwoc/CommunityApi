package io.github.cowwoc.capi.fizz;

import io.github.cowwoc.capi.core.Browser;
import io.github.cowwoc.capi.core.Download;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.cowwoc.requirements12.java.DefaultJavaValidators.requireThat;

/**
 * Utilities for the <a href="https://fizz.ca/">Fizz website</a>.
 */
public final class Fizz
{
	private static final By SETTINGS = By.id("menuSettingsLink");
	private static final By TRANSACTIONS = By.id("transactionHistory");
	private static final By YEAR_SELECTOR = By.cssSelector("#default");
	private static final By YEARS = By.cssSelector("div > div > div.dropdown-menu-custom-years > button");
	private static final By PAGE_LOADING = By.cssSelector(".spinner");
	private static final By SEE_MORE = By.cssSelector("div.mt-24 > a");
	private static final By STATEMENT = By.cssSelector("table > tr");
	private static final By STATEMENT_DATE = By.cssSelector("td:nth-child(1)");
	private static final By ACCOUNT_NUMBER = By.cssSelector("td:nth-child(3)");
	private static final By DOWNLOAD_BUTTON = By.cssSelector(".non-printable > button");
	private static final By CLOSE_BUTTON = By.cssSelector("modal-container .flex-column > button");
	// Example: 2025/06/22
	private static final Pattern YEAR_AND_MONTH = Pattern.compile("^(\\w+)/(\\w+)/\\w+");
	private final Browser browser;
	private final WebDriver client;
	private final Logger log = LoggerFactory.getLogger(Fizz.class);

	/**
	 * Creates a new instance.
	 *
	 * @param browser a browser whose active tab is already logged into the website
	 * @throws NullPointerException if {@code browser} is null
	 */
	public Fizz(Browser browser)
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
	public List<FizzAccountStatement> download(Predicate<String> accountNumber,
		Predicate<FizzStatementMetadata> statements)
	{
		WebElement settings = client.findElement(SETTINGS);
		browser.click(settings);

		WebElement transactions = client.findElement(TRANSACTIONS);
		browser.click(transactions);

		WebDriverWait wait = new WebDriverWait(client, Duration.ofMinutes(1));
		wait.until(_ -> browser.elementExists(PAGE_LOADING));
		wait.until(_ -> !browser.elementExists(PAGE_LOADING));

		List<FizzAccountStatement> downloads = new ArrayList<>();
		Set<String> processed = new HashSet<>();
		while (true)
		{
			wait.until(_ -> browser.elementExists(YEAR_SELECTOR));
			WebElement yearSelectorElement = client.findElement(YEAR_SELECTOR);
			browser.click(yearSelectorElement);

			WebElement yearElement = selectNextYear(processed);
			if (yearElement == null)
				break;

			log.info("Selecting year");
			log.info("yearElement: {}", yearElement.getText());
			log.info("yearSelectorElement: {}", yearSelectorElement.getText());
			if (yearElement.getText().equals(yearSelectorElement.getText()))
			{
				// WORKAROUND: If we select the current year, the spinner runs forever.
				browser.click(yearSelectorElement);
			}
			else
			{
				browser.click(yearElement);
				log.info("Waiting for page load");

				wait.until(_ -> browser.elementExists(PAGE_LOADING));
				wait.until(_ -> !browser.elementExists(PAGE_LOADING));
			}
			log.info("Expanding listing");

			// Expand the list of statements
			while (true)
			{
				List<WebElement> seeMore = client.findElements(SEE_MORE);
				if (seeMore.isEmpty())
					break;
				browser.click(seeMore.getFirst());
			}

			for (WebElement statement : client.findElements(STATEMENT))
			{
				String actualAccountNumber = statement.findElement(ACCOUNT_NUMBER).getText();
				if (!accountNumber.test(actualAccountNumber))
					continue;
				String date = statement.findElement(STATEMENT_DATE).getText();
				log.info("Stepping into account {} with date {}", actualAccountNumber, date);
				Matcher matcher = YEAR_AND_MONTH.matcher(date);
				boolean matchFound = matcher.find();
				if (matchFound)
				{
					int year = Integer.parseInt(matcher.group(1));
					int month = Integer.parseInt(matcher.group(2));
					LocalDate firstDay = LocalDate.of(year, month, 1);
					if (statements.test(new FizzStatementMetadata(actualAccountNumber, firstDay)))
					{
						Instant now = Instant.now();
						browser.click(statement);

						WebElement downloadButton = client.findElement(DOWNLOAD_BUTTON);
						browser.click(downloadButton);
						Download download = browser.waitForDownload(now);
						downloads.add(new FizzAccountStatement(download.target(), actualAccountNumber, firstDay));

						WebElement closeButton = client.findElement(CLOSE_BUTTON);
						browser.click(closeButton);
					}
				}
			}
		}
		return downloads;
	}

	private WebElement selectNextYear(Set<String> processed)
	{
		// Elements must be reloaded each time because Fizz won't let us open a new tab and the old elements
		// become stale.
		List<WebElement> yearElements = client.findElements(YEARS);
		for (WebElement element : yearElements)
		{
			if (processed.add(element.getText()))
				return element;
			log.info("Skipping {}", element.getText());
		}
		return null;
	}
}