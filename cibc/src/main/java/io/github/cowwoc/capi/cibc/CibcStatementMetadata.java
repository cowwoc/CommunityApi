package io.github.cowwoc.capi.cibc;

import java.time.LocalDate;

import static com.github.cowwoc.requirements10.java.DefaultJavaValidators.requireThat;

/**
 * The metadata of an account statement.
 *
 * @param accountNumber the account number
 * @param firstDay      the first day that is included in the statement
 */
public record CibcStatementMetadata(String accountNumber, LocalDate firstDay)
{
	/**
	 * Creates a new instance.
	 *
	 * @param accountNumber the account number
	 * @param firstDay      the first day that is included in the statement
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code accountNumber} contains leading or trailing whitespace or is
	 *                                  empty
	 */
	public CibcStatementMetadata
	{
		requireThat(accountNumber, "accountNumber").isStripped().isNotEmpty();
		requireThat(firstDay, "firstDay").isNotNull();
	}
}