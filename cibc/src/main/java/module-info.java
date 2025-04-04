module io.github.cowwoc.capi.cibc
{
	requires io.github.cowwoc.capi.core;

	requires com.github.cowwoc.requirements10.java;
	requires com.github.cowwoc.pouch.core;
	requires org.slf4j;
	requires java.net.http;
	requires org.seleniumhq.selenium.chrome_driver;
	requires org.seleniumhq.selenium.devtools_v131;
	requires org.seleniumhq.selenium.support;
	requires transitive org.seleniumhq.selenium.api;

	exports io.github.cowwoc.capi.cibc;
}