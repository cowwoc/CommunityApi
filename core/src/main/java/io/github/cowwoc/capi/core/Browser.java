package io.github.cowwoc.capi.core;

import io.github.cowwoc.pouch.core.WrappedCheckedException;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriver.Timeouts;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static io.github.cowwoc.requirements12.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Connects to an existing browser.
 */
public final class Browser
{
	/**
	 * The maximum amount of time that it will take for a page to load or an element to show up after an
	 * interaction with the page.
	 */
	private static final Duration IMPLICIT_WAIT_TIME = Duration.ofSeconds(5);

	/**
	 * Connects to an existing Chrome browser.
	 * <p>
	 * The browser command-line must include the following options:
	 * <p>
	 * {@code --remote-debugging-address=<ip> --remote-debugging-port=<port> --disable-save-password-bubble
	 * --safebrowsing-disable-auto-update --safebrowsing.enabled=false --user-data-dir=<non-standard
	 * directory>}
	 * <p>
	 * Disable "Ask where to save each file before downloading".
	 * <p>
	 * Prefer binding {@code remote-debugging-address} to a specific IP address, as Chrome and Java may resolve
	 * {@code localhost} differently.
	 *
	 * @param address the address of Chrome's REST endpoint
	 * @return a new client
	 * @throws NullPointerException if {@code address} is null
	 * @see <a href="https://developer.chrome.com/blog/remote-debugging-port">--user-data-dir must point to a
	 * 	non-standard directory</a>
	 */
	public static Browser connectToChrome(InetSocketAddress address)
	{
		Logger log = LoggerFactory.getLogger(Browser.class);

		// Chrome will ignore --remote-debugging-port and --remote-debugging-pipe unless --user-data-dir is also
		// specified and points to a non-standard directory.
		// Source: https://developer.chrome.com/blog/remote-debugging-port
		ChromeOptions options = new ChromeOptions();
		String debuggerAddress = address.getHostString() + ":" + address.getPort();
		options.setExperimentalOption("debuggerAddress", debuggerAddress);
		log.info("Connecting to {}", debuggerAddress);
		log.info("Options {}", options);
		ChromeDriver client = new ChromeDriver(options);
		log.info("Connection established");

		client.manage().window().maximize();
		client.manage().timeouts().implicitlyWait(IMPLICIT_WAIT_TIME);

		Runtime.getRuntime().addShutdownHook(new Thread(client::quit));
		return new Browser(client);
	}

	private final WebDriver webDriver;

	/**
	 * Creates a new Browser.
	 *
	 * @param webDriver the underlying WebDriver
	 */
	private Browser(WebDriver webDriver)
	{
		assert webDriver != null;
		this.webDriver = webDriver;
	}

	/**
	 * Returns the underlying {@code WebDriver}.
	 *
	 * @return the WebDriver
	 */
	public WebDriver getWebDriver()
	{
		return webDriver;
	}

	/**
	 * Clicks on an element which opens a new window and switches to it.
	 *
	 * @param element the element to click on
	 */
	public void clickToOpenWindow(WebElement element)
	{
		Set<String> oldWindows = webDriver.getWindowHandles();
		element.click();
		WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
		Set<String> newWindows = wait.until(_ ->
		{
			Set<String> candidate = webDriver.getWindowHandles();
			if (candidate.size() > oldWindows.size())
				return candidate;
			return null;
		});

		for (String window : newWindows)
		{
			if (!oldWindows.contains(window))
			{
				webDriver.switchTo().window(window);
				break;
			}
		}
	}

	/**
	 * Checks if an element exists.
	 *
	 * @param by the search criteria
	 * @return {@code true} if the element was found
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean elementExists(By by)
	{
		return elementExists(webDriver, by);
	}

	/**
	 * Checks if an element exists.
	 *
	 * @param parent the element to begin search at
	 * @param by     the search criteria
	 * @return {@code true} if the element was found
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean elementExists(SearchContext parent, By by)
	{
		Timeouts timeouts = webDriver.manage().timeouts();
		Duration original = timeouts.getImplicitWaitTimeout();
		timeouts.implicitlyWait(Duration.ZERO);
		List<WebElement> matches = parent.findElements(by);
		timeouts.implicitlyWait(original);
		return !matches.isEmpty();
	}

	/**
	 * Clicks an element. If it isn't clickable, waits and tries again.
	 *
	 * @param element the element
	 */
	public void click(WebElement element)
	{
		for (int i = 0; true; ++i)
		{
			try
			{
				element.click();
				return;
			}
			catch (ElementClickInterceptedException e)
			{
				if (i == 2)
					throw e;
				// Sometimes a page header covers the element, so we try scrolling further up
				scrollToElementWithOffset(element, i * 100);
				WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofMinutes(1));
				wait.until(_ -> ExpectedConditions.elementToBeClickable(element));
			}
		}
	}

	private void scrollToElementWithOffset(WebElement element, int offset)
	{
		JavascriptExecutor js = (JavascriptExecutor) webDriver;
		js.executeScript("""
				const element = arguments[0];
				const boundingBox = element.getBoundingClientRect();
				window.scrollBy(0, boundingBox.top - arguments[1]);""",
			element, offset);
	}

	/**
	 * Waits for a download to complete and returns its information.
	 *
	 * @param startAt the earliest time that the download may have been initialized
	 * @return the downloaded file.
	 */
	public Download waitForDownload(Instant startAt)
	{
		// Based on https://stackoverflow.com/a/56570364/14731
		String mainWindow = webDriver.getWindowHandle();
		webDriver.switchTo().newWindow(WindowType.TAB);
		webDriver.get("chrome://downloads");

		// Execute Javascript in a new tab
		JavascriptExecutor js = (JavascriptExecutor) webDriver;
		SearchContext downloadsManagerRoot = webDriver.findElement(By.cssSelector("downloads-manager")).
			getShadowRoot();

		// Wait for the download to begin
		WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofMinutes(1));
		SearchContext downloadItemRoot = wait.until(_ ->
		{
			SearchContext candidate = downloadsManagerRoot.findElement(By.cssSelector("downloads-item")).
				getShadowRoot();

			Path targetPath = getDownloadTargetPath(candidate);
			try
			{
				FileTime lastModifiedTime = Files.getLastModifiedTime(targetPath);
				if (!lastModifiedTime.toInstant().isBefore(startAt))
					return candidate;
			}
			catch (IOException _)
			{
			}
			return null;
		});

		wait.until(_ ->
		{
			// ShadowRoot throws "org.openqa.selenium.InvalidArgumentException: invalid argument: invalid locator"
			// if By.id() is used, but By.cssSelector() works fine. Go figure.
			WebElement tag = downloadItemRoot.findElement(By.cssSelector("#tag"));
			// Use innerText to strip away comments
			String tagText = (String) js.executeScript("return arguments[0].innerText", tag);
			assert tagText != null;

			if (!tagText.isEmpty())
				throw WrappedCheckedException.wrap(new IOException("Download failed: " + tagText));
			// If the download did not fail and a progress bar is not present, then it is assumed to have completed
			// successfully.
			return !elementExists(downloadItemRoot, By.cssSelector("#progress"));
		});
		WebElement fileLink = downloadItemRoot.findElement(By.cssSelector("#file-link"));

		String source = fileLink.getDomAttribute("href");
		assert source != null;
		if (source.startsWith("blob:"))
			source = source.substring("blob:".length());

		Path target = getDownloadTargetPath(downloadItemRoot);

		// Close the download tab
		webDriver.close();
		webDriver.switchTo().window(mainWindow);
		return new Download(URI.create(source), target);
	}

	private Path getDownloadTargetPath(SearchContext downloadItemRoot)
	{
		// ShadowRoot throws "org.openqa.selenium.InvalidArgumentException: invalid argument: invalid locator"
		// if By.id() is used, but By.cssSelector() works fine. Go figure.
		String target = downloadItemRoot.findElement(By.cssSelector("#file-icon")).getDomAttribute("src");
		assert target != null;
		requireThat(target, "target").startsWith("chrome://fileicon/?path=");

		URI targetAsUri = URI.create(target);
		target = getQueryParameter(targetAsUri, "path");
		assert target != null;
		target = URLDecoder.decode(target, UTF_8);
		return Paths.get(target);
	}

	/**
	 * Returns the value of a query parameter.
	 *
	 * @param uri  the URI
	 * @param name the name of the query parameter
	 * @return null if the query parameter was not found
	 */
	private String getQueryParameter(URI uri, String name)
	{
		for (String pair : uri.getQuery().split("&"))
		{
			String[] keyValue = pair.split("=");
			String key = keyValue[0];
			if (key.equals(name))
				return keyValue[1];
		}
		return null;
	}
}