package org.nopware.librestcli;

import org.junit.jupiter.api.Test;

import com.google.common.io.Resources;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for making clear specifications of picocli.
 */
@Slf4j
public class PicoCliSpecTest {
    /*
     * Let's use the GitHub API as an example.
     */
    private static final String GITHUB_API_SPEC;

    static {
        try {
            long begin = System.currentTimeMillis();
            GITHUB_API_SPEC = Resources.toString(
                    Resources.getResource("api.github.com.json"), Charset.defaultCharset());
            long end = System.currentTimeMillis();
            log.debug("Parsing the GitHub API took {} ms.", end - begin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Make clear the specification of
     * {@link picocli.CommandLine.ParseResult#matchedOptionValue(String, Object)}.
     */
    @Test
    public void testOptionalOptionValue() {
        OptionSpec optionWithOptionalValue = OptionSpec.builder("--option-with-optional-value")
                .arity("0..1")
                .description("Option with optional value")
                .paramLabel("value")
                .type(String.class)
                .build();

        CommandSpec commandSpec = CommandSpec.create();
        commandSpec.addOption(optionWithOptionalValue);

        AtomicReference<String> optionalValueHolder = new AtomicReference<>();

        CommandLine commandLine = new CommandLine(commandSpec);
        commandLine.setExecutionStrategy(parseResult -> {
            String optionValue = parseResult.matchedOptionValue(optionWithOptionalValue.longestName(),
                    "This default value is not used even when the arity is 0. I don't know when this default value is used.");
            optionalValueHolder.set(optionValue);
            return 0;
        });

        // Case: Arity is 0.
        commandLine.execute("--option-with-optional-value");
        assertThat(optionalValueHolder.get()).isEqualTo(""); // It is not default value but empty string.

        // Case: Arity is 1.
        commandLine.execute("--option-with-optional-value", "oneValue");
        assertThat(optionalValueHolder.get()).isEqualTo("oneValue");
    }

    /**
     * Measure the time to parse big OpenAPI document.
     */
    @Test
    public void measureParsingTime() {
        long begin = System.currentTimeMillis();

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(GITHUB_API_SPEC, null, parseOptions);
        OpenAPI openAPI = swaggerParseResult.getOpenAPI();

        long end = System.currentTimeMillis();

        System.out.println(openAPI.getInfo().getSummary());

        System.out.printf("Parsing the GitHub API took %d ms.%n", end - begin);
    }

    /**
     * Test if CommandSpec is immutable.
     * If it is immutable, we can reuse the same CommandSpec instance for multiple CommandLine instances.
     * Let's test its immutability by checking the hash code of CommandLine before and after executing a command.
     * It is a very rough test, but it is enough for now.
     * <p>
     * This test should be dependent only on picocli, but this test uses RestCli because constructing command-spec is too tedious other than by RestCli :-)
     */
    @Test
    public void testIfCommandSpecIsImmutable() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = RestCli.class.getDeclaredMethod("createCommandSpec", OpenAPI.class);
        method.setAccessible(true);

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult swaggerParseResult = new OpenAPIV3Parser().readContents(GITHUB_API_SPEC, null, parseOptions);
        OpenAPI openAPI = swaggerParseResult.getOpenAPI();
        CommandSpec commandSpec = (CommandSpec) method.invoke(RestCli.class, openAPI);

        CommandLine commandLine = new CommandLine(commandSpec);
        long hash = commandLine.hashCode();

        commandLine.execute("/repos/{owner}/{repo}/issues", "get", "--owner=naoshi-higuchi", "--repo=flist");

        assertThat(commandLine.hashCode()).isEqualTo(hash);
    }
}
