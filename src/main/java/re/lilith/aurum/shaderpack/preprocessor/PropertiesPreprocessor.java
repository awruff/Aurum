package re.lilith.aurum.shaderpack.preprocessor;

import org.anarres.cpp.*;
import re.lilith.aurum.Aurum;
import re.lilith.aurum.shaderpack.StringPair;
import re.lilith.aurum.shaderpack.option.ShaderPackOptions;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class PropertiesPreprocessor {
    private static final String RAW_LINE_MARKER = "AURUM_RAWLINE_";

    // Derived from ShaderProcessor.glslPreprocessSource, which is derived from GlShader from Canvas, licenced under LGPL
    public static String preprocessSource(String source, ShaderPackOptions shaderPackOptions, Iterable<StringPair> environmentDefines) {
        if (source.contains(PropertyCollectingListener.PROPERTY_MARKER) || source.contains("AURUM_PASSTHROUGHBACKSLASH") || source.contains(RAW_LINE_MARKER)) {
            throw new RuntimeException("Some shader author is trying to exploit internal Aurum implementation details, stop!");
        }

        List<String> booleanValues = getBooleanValues(shaderPackOptions);
        Map<String, String> stringValues = getStringValues(shaderPackOptions);

        try (Preprocessor pp = new Preprocessor()) {
            for (String value : booleanValues) {
                pp.addMacro(value);
            }

            for (StringPair envDefine : environmentDefines) {
                if (envDefine.getValue().isEmpty()) {
                    pp.addMacro(envDefine.getKey());
                } else {
                    pp.addMacro(envDefine.getKey(), envDefine.getValue());
                }
            }

            stringValues.forEach((name, value) -> {
                try {
                    pp.addMacro(name, value);
                } catch (LexerException e) {
                    Aurum.LOGGER.error(e);
                }
            });

            return process(pp, source);
        } catch (IOException e) {
            throw new RuntimeException("Unexpected IOException while processing macros", e);
        } catch (LexerException e) {
            throw new RuntimeException("Unexpected LexerException processing macros", e);
        }
    }

    private static String process(Preprocessor preprocessor, String source) {
        preprocessor.setListener(new PropertiesCommentListener());
        PropertyCollectingListener listener = new PropertyCollectingListener();
        preprocessor.setListener(listener);

        // Not super efficient, but this removes trailing whitespace on lines, fixing an issue with whitespace after
        // line continuations (see PreprocessorTest#testWeirdPropertiesLineContinuation)
        // Required for Voyager Shader
        List<String> rawLines = new ArrayList<>();
        source = Arrays.stream(source.split("\\R")).map(String::trim).filter(s -> !s.isBlank())
                .map(line -> {
                    // Feature flag names are plain words that packs also use as macro names (e.g. an SSBO include
                    // guard), so these lines must not be macro-expanded.
                    if (line.startsWith("iris.features.")) {
                        rawLines.add(line);
                        return RAW_LINE_MARKER + (rawLines.size() - 1);
                    }

                    if (line.startsWith("#")) {
                        for (PreprocessorCommand command : PreprocessorCommand.values()) {
                            if (line.startsWith("#" + command.name().replace("PP_", "").toLowerCase(Locale.ROOT))) {
                                return line;
                            }
                        }
                        return "";
                    }
                    return line.replace("#", "");
                }).collect(Collectors.joining("\n")) + "\n";
        // Hides backslashes from JCPP so that line continuations in property values survive preprocessing.
        source = source.replace("\\", "AURUM_PASSTHROUGHBACKSLASH");

        preprocessor.addInput(new StringLexerSource(source, true));
        preprocessor.addFeature(Feature.KEEPCOMMENTS);

        final StringBuilder builder = new StringBuilder();

        try {
            for (; ; ) {
                final Token tok = preprocessor.token();
                if (tok == null || tok.getType() == Token.EOF) break;
                builder.append(tok.getText());
            }
        } catch (final Exception e) {
            Aurum.LOGGER.error("Properties pre-processing failed", e);
        }

        source = builder.toString();

        source = (listener.collectLines() + source).replace("AURUM_PASSTHROUGHBACKSLASH", "\\");

        for (int i = rawLines.size() - 1; i >= 0; i--) {
            source = source.replace(RAW_LINE_MARKER + i, rawLines.get(i));
        }

        return source;
    }

    private static List<String> getBooleanValues(ShaderPackOptions shaderPackOptions) {
        List<String> booleanValues = new ArrayList<>();

        shaderPackOptions.getOptionSet().getBooleanOptions().forEach((string, _) -> {
            boolean trueValue = shaderPackOptions.getOptionValues().getBooleanValueOrDefault(string);

            if (trueValue) {
                booleanValues.add(string);
            }
        });

        return booleanValues;
    }

    private static Map<String, String> getStringValues(ShaderPackOptions shaderPackOptions) {
        Map<String, String> stringValues = new HashMap<>();

        shaderPackOptions.getOptionSet().getStringOptions().forEach(
                (optionName, _) -> stringValues.put(optionName, shaderPackOptions.getOptionValues().getStringValueOrDefault(optionName)));

        return stringValues;
    }
}
