package io.github.cowwoc.capi.core;

import java.net.URI;
import java.nio.file.Path;

import static com.github.cowwoc.requirements10.java.DefaultJavaValidators.requireThat;

/**
 * A file that was downloaded by the browser.
 *
 * @param source the URL that was downloaded
 * @param target the path of the file on disk
 */
public record Download(URI source, Path target)
{
	/**
	 * @param source the URL that was downloaded
	 * @param target the path of the file on disk
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Download
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
	}
}
