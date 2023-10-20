package org.nopware.librestcli;

import com.google.common.io.Resources;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;

@Slf4j
public class SwaggerParserSpecTest {
    /*
     * Let's use the GitHub API as an example.
     */
    private static final String GITHUB_API_SPEC;

    static {
        try {
            GITHUB_API_SPEC = Resources.toString(
                    Resources.getResource("api.github.com.json"), Charset.defaultCharset());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

        System.out.println(openAPI.getInfo().getDescription());

        System.out.printf("Parsing the GitHub API took %d ms.%n", end - begin);
    }
}
