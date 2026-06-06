/**
 * Royal Bank.
 */
module io.github.cowwoc.capi.rbc
{
	requires transitive io.github.cowwoc.capi.core;
	requires io.github.cowwoc.pouch10.core;
	requires io.github.cowwoc.requirements13.java;
	requires org.slf4j;
	requires org.seleniumhq.selenium.chrome_driver;
	requires org.seleniumhq.selenium.support;
	requires transitive org.seleniumhq.selenium.api;

	exports io.github.cowwoc.capi.rbc;
}