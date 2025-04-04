package io.github.cowwoc.capi.cibc;

import com.github.cowwoc.pouch.core.WrappedCheckedException;
import io.github.cowwoc.capi.core.Download;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.cowwoc.requirements10.java.DefaultJavaValidators.requireThat;

/**
 * Utilities for the <a href="https://www.cibc.com/">CIBC website</a>.
 */
public final class Cibc
{
	private static final By MY_DOCUMENTS = By.cssSelector(".docHub a");
	private static final By ACCOUNT_STATEMENTS = By.xpath("//a[span[contains(text(),'Account statements')]]");
	private static final By ACCOUNT_NUMBER = By.cssSelector("span.account-info span:nth-of-type(2)");
	private static final By COLLAPSED_YEARS = By.cssSelector("div.ui-collapsible-pane.ui-collapsed button");
	private static final By STATEMENT_DATE = By.cssSelector("ul.statement-list div.tile-content span");
	private static final Pattern MONTH_AND_YEAR = Pattern.compile("(\\w+)\\s+\\w+\\s+\\w+\\s+\\w+,\\s+(\\w+)");
	private final WebDriver client;
	private final Logger log = LoggerFactory.getLogger(Cibc.class);

	/**
	 * Creates a new instance.
	 *
	 * @param client a browser whose active tab is already logged into the website
	 * @throws NullPointerException if {@code client} is null
	 */
	public Cibc(WebDriver client)
	{
		requireThat(client, "client").isNotNull();
		this.client = client;
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
		try
		{
			WebElement myDocuments = client.findElement(MY_DOCUMENTS);
			myDocuments.click();

			WebElement accountStatements = client.findElement(ACCOUNT_STATEMENTS);
			accountStatements.click();

			List<CibcAccountStatement> downloads = new ArrayList<>();
			for (WebElement accountNumberElement : client.findElements(ACCOUNT_NUMBER))
			{
				String accountNumberText = accountNumberElement.getText();
				if (accountNumber.test(accountNumberText))
				{
					log.info("Stepping into account number: {}", accountNumberText);
					accountNumberElement.click();
					downloads.addAll(downloadStatements(accountNumberText, statements));
				}
			}
			return downloads;
		}
		catch (NoSuchElementException e)
		{
			log.debug("Page source: {}", client.getPageSource());
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
			if (matcher.find())
			{
				int month = Integer.parseInt(matcher.group(1));
				int year = Integer.parseInt(matcher.group(2));
				LocalDate firstDay = LocalDate.of(year, month, 1);
				if (statements.test(new CibcStatementMetadata(accountNumber, firstDay)))
				{
					statement.click();
					Download download = waitForDownload();
					downloads.add(new CibcAccountStatement(download.target(), accountNumber, firstDay));
				}
			}
		}
		return downloads;
	}

	private Download waitForDownload()
	{
		// Based on https://stackoverflow.com/a/56570364/14731
		String mainWindow = client.getWindowHandle();
		client.get("chrome://downloads");

		// Execute Javascript in a new tab
		JavascriptExecutor js = (JavascriptExecutor) client;
		// Look up the first element
		WebElement downloadItemComponent = client.findElement(By.cssSelector("downloads-item"));
		WebElement downloadItem = (WebElement) js.executeScript("return arguments[0].shadowRoot",
			downloadItemComponent);
		assert downloadItem != null;

		WebDriverWait wait = new WebDriverWait(client, Duration.ofMinutes(1));
		wait.until(_ ->
		{
			WebElement tag = downloadItem.findElement(By.id("tag"));
			// innerText strips away comments
			String tagText = (String) js.executeScript("return arguments[0].innerText", tag);
			assert tagText != null;

			if (!tagText.isEmpty())
				throw WrappedCheckedException.wrap(new IOException("Download failed: " + tagText));
			// If the download did not fail and a progress bar is not present then it is assumed to have completed
			// successfully.
			List<WebElement> progressBar = downloadItem.findElements(By.id("progress"));
			return progressBar.isEmpty();
		});
		WebElement fileLink = downloadItem.findElement(By.id("file-link"));
		String source = fileLink.getDomAttribute("href");
		assert source != null;
		String target = downloadItem.findElement(By.id("file-icon")).getDomAttribute("src");
		assert target != null;
		// Close the download tab
		client.close();
		client.switchTo().window(mainWindow);
		return new Download(URI.create(source), Paths.get(URI.create(target)));
	}
}