package io.github.cowwoc.capi.core;

/**
 * String helper functions.
 */
public final class Strings
{
	/**
	 * Pads a string on the right until its length is equal to a desired length.
	 *
	 * @param source        a string
	 * @param desiredLength the desired length for the string
	 * @param padding       the character used to pad the string
	 * @return {@code source} if its length is already equal to or greater than {@code desiredLength}
	 */
	public static String padRight(String source, int desiredLength, char padding)
	{
		int count = desiredLength - source.length();
		if (count < 0)
			return source;
		return source + String.valueOf(padding).repeat(count);
	}

	private Strings()
	{
	}
}