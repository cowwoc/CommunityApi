package io.github.cowwoc.capi.fizz;

import java.nio.file.Path;
import java.time.LocalDate;

import static io.github.cowwoc.requirements12.java.DefaultJavaValidators.requireThat;

/**
 * An account statement.
 *
 * @param path          the path of the statement
 * @param accountNumber the account number
 * @param firstDay      the first day that is included in the statement
 */
public record FizzAccountStatement(Path path, String accountNumber, LocalDate firstDay)
{
	/**
	 * Creates a new statement.
	 *
	 * @param path          the path of the statement
	 * @param accountNumber the account number
	 * @param firstDay      the first day that is included in the statement
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code accountNumber} contains leading or trailing whitespace or is
	 *                                  empty
	 */
	public FizzAccountStatement
	{
		requireThat(path, "path").isNotNull();
		requireThat(accountNumber, "accountNumber").isStripped().isNotEmpty();
		requireThat(firstDay, "firstDay").isNotNull();
	}
}