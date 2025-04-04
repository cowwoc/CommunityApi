package io.github.cowwoc.capi.core;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;

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
	 * --disable-download-notification --safebrowsing-disable-auto-update --safebrowsing.enabled=false
	 * --download.prompt_for_download=false}
	 * <p>
	 * Prefer binding {@code remote-debugging-address} to a specific IP address, as Chrome and Java may resolve
	 * {@code localhost} differently.
	 *
	 * @param address the address of Chrome's REST endpoint
	 * @return a new client
	 * @throws NullPointerException if {@code address} is null
	 */
	public static WebDriver connectToChrome(InetSocketAddress address)
	{
		Logger log = LoggerFactory.getLogger(Browser.class);

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
		return client;
	}

	private Browser()
	{
	}
}