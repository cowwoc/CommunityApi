package io.github.cowwoc.capi.rbc;

import static io.github.cowwoc.requirements12.java.DefaultJavaValidators.requireThat;

/**
 * @param cardNumber the account's card number
 * @param password   the account's password
 */
public record RbcCredentials(String cardNumber, String password)
{
	/**
	 * Creates a new instance.
	 *
	 * @param cardNumber the account's card number
	 * @param password   the account's password
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if any of the arguments contain leading or trailing whitespace or are
	 *                                  empty
	 */
	public RbcCredentials
	{
		requireThat(cardNumber, "cardNumber").isStripped().isNotEmpty();
		requireThat(password, "password").isStripped().isNotEmpty();
	}
}