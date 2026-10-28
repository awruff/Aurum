package re.lilith.aurum.pipeline.transform.impl;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.taumc.glsl.ShaderPrinter;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;
import org.taumc.glsl.grammar.GLSLParserBaseListener;
import re.lilith.aurum.Aurum;
import re.lilith.aurum.pipeline.transform.patch.Parameters;
import re.lilith.aurum.pipeline.transform.patch.Patch;
import re.lilith.aurum.pipeline.transform.patch.PatchShaderType;

import java.util.EnumMap;
import java.util.HashMap;

public class CompatibilityTransformer {

    private static final PatchShaderType[] pipeline = {PatchShaderType.VERTEX, PatchShaderType.GEOMETRY, PatchShaderType.FRAGMENT};

    // Packs written for modern versions sometimes only declare a uniform behind an MC_VERSION check.
    private static final String[] BUILTIN_UNIFORMS = {
            "bool", "feetInWater firstPersonCamera hasCeiling hasSkylight hideGUI isRiding isRightHanded isSpectator vehicleInWater",
            "float", "alphaTestRef aspectRatio blindness currentPlayerAir currentPlayerArmor currentPlayerHealth currentPlayerHunger darknessFactor darknessLightFactor eyeAltitude far fogDensity fogEnd fogStart frameTime frameTimeCounter lastFrameTime maxPlayerAir maxPlayerArmor maxPlayerHealth maxPlayerHunger near nightVision playerMood rainStrength rainfall screenBrightness shadowAngle sunAngle temperature thunderStrength viewHeight viewWidth wetness",
            "int", "bedrockLevel biome biome_category biome_precipitation currentSelectedBlockId fogMode fogShape frameCounter heightLimit heldBlockLightValue heldBlockLightValue2 heldItemId heldItemId2 isEyeInWater logicalHeightLimit moonPhase renderStage seaLevel vehicleId worldDay worldTime",
            "ivec2", "atlasSize currentYearTime eyeBrightness eyeBrightnessSmooth gtextureSize",
            "ivec3", "cameraPositionInt currentDate currentTime previousCameraPositionInt",
            "ivec4", "blendFunc",
            "mat4", "gbufferModelView gbufferModelViewInverse gbufferPreviousModelView gbufferPreviousProjection gbufferProjection gbufferProjectionInverse shadowModelView shadowModelViewInverse shadowProjection shadowProjectionInverse",
            "vec3", "cameraPosition cameraPositionFract currentSelectedBlockPos eyePosition fogColor heldBlockLightColor moonPosition playerBodyVector playerLookVector previousCameraPosition previousCameraPositionFract relativeEyePosition shadowLightPosition skyColor sunPosition upPosition",
            "vec4", "entityColor lightningBoltPosition"
    };

    public static void transformEach(Transformer transformer, Parameters parameters) {
        if (parameters.type == PatchShaderType.VERTEX) {
            // This is a hacky patch for Sildur's Shaders that changes the way it does its waving water to make it work better
            // See https://github.com/IrisShaders/Iris/issues/509

            String oldCode = "fract(worldpos.y + 0.001)";
            String newCode = "fract(worldpos.y + 0.01)";

            transformer.replaceExpression(oldCode, newCode, GLSLParser::binary_expression);
            transformer.replaceExpression(oldCode, newCode, GLSLParser::postfix_expression);
        }

        if (parameters.type == PatchShaderType.FRAGMENT && parameters.patch != Patch.COMPOSITE) {
            injectAlphaTestDiscard(transformer);
        }

        renameLegacyTextureFunctions(transformer);
        injectInverseIfMissing(transformer, parameters.version);
        injectFogCompatIfNeeded(transformer);
        injectImplicitIdUniforms(transformer);

        transformer.removeUnusedFunctions();
        transformer.removeConstAssignment();
        transformer.rewriteStructArrays();
        upgradeStorageQualifiers(transformer, parameters.type);
    }

    /**
     * Every shader is compiled as at least GLSL 130, where Mesa rejects qualifiers such as {@code flat} on the
     * deprecated {@code attribute}/{@code varying} storage, so those become {@code in}/{@code out}.
     */
    private static void upgradeStorageQualifiers(Transformer transformer, PatchShaderType type) {
        for (TerminalNode node : transformer.collectStorage()) {
            if (!(node.getSymbol() instanceof CommonToken token)) {
                continue;
            }

            if (token.getType() == GLSLLexer.ATTRIBUTE && type == PatchShaderType.VERTEX) {
                token.setType(GLSLLexer.IN);
                token.setText("in");
            } else if (token.getType() == GLSLLexer.VARYING) {
                if (type == PatchShaderType.VERTEX) {
                    token.setType(GLSLLexer.OUT);
                    token.setText("out");
                } else if (type == PatchShaderType.FRAGMENT) {
                    token.setType(GLSLLexer.IN);
                    token.setText("in");
                }
            }
        }
    }

    public static void transformCompute(Transformer transformer, int version) {
        renameLegacyTextureFunctions(transformer);
        injectInverseIfMissing(transformer, version);
        injectFogCompatIfNeeded(transformer);

        transformer.removeUnusedFunctions();
        transformer.removeConstAssignment();
        transformer.rewriteStructArrays();
    }

    /**
     * Iris substitutes these IDs wherever they are referenced, so packs may use them without declaring them.
     */
    private static void injectImplicitIdUniforms(Transformer transformer) {
        for (String name : new String[]{"entityId", "blockEntityId", "currentRenderedItemId"}) {
            if (transformer.containsCall(name) && !transformer.hasVariable(name)) {
                transformer.injectVariable("uniform int " + name + ";");
            }
        }

        for (int i = 0; i < BUILTIN_UNIFORMS.length; i += 2) {
            for (String name : BUILTIN_UNIFORMS[i + 1].split(" ")) {
                if (transformer.containsCall(name) && !transformer.hasVariable(name)) {
                    transformer.injectVariable("uniform " + BUILTIN_UNIFORMS[i] + " " + name + ";");
                }
            }
        }
    }

    private static void injectFogCompatIfNeeded(Transformer transformer) {
        if (!transformer.containsCall("gl_Fog")) {
            return;
        }

        transformer.rename("gl_Fog", "aurum_Fog");
        transformer.injectVariable("uniform float aurum_FogDensity;");
        transformer.injectVariable("uniform float aurum_FogStart;");
        transformer.injectVariable("uniform float aurum_FogEnd;");
        transformer.injectVariable("uniform vec4 aurum_FogColor;");
        transformer.injectFunction("struct aurum_FogParameters {vec4 color;float density;float start;float end;float scale;};");
        transformer.injectFunction("aurum_FogParameters aurum_Fog = aurum_FogParameters(aurum_FogColor, aurum_FogDensity, aurum_FogStart, aurum_FogEnd, 1.0f / (aurum_FogEnd - aurum_FogStart));");
    }

    private static void injectInverseIfMissing(Transformer transformer, int version) {
        if (version >= 140 || !transformer.containsCall("inverse")) {
            return;
        }

        transformer.injectFunction(
                "mat2 inverse(mat2 m) {" +
                        "return mat2(m[1][1], -m[0][1], -m[1][0], m[0][0]) / (m[0][0] * m[1][1] - m[0][1] * m[1][0]);" +
                        "}");

        transformer.injectFunction(
                "mat3 inverse(mat3 m) {" +
                        "float b01 = m[2][2] * m[1][1] - m[1][2] * m[2][1];" +
                        "float b11 = -m[2][2] * m[1][0] + m[1][2] * m[2][0];" +
                        "float b21 = m[2][1] * m[1][0] - m[1][1] * m[2][0];" +
                        "float det = m[0][0] * b01 + m[0][1] * b11 + m[0][2] * b21;" +
                        "return mat3(" +
                        "b01, -m[2][2] * m[0][1] + m[0][2] * m[2][1], m[1][2] * m[0][1] - m[0][2] * m[1][1]," +
                        "b11, m[2][2] * m[0][0] - m[0][2] * m[2][0], -m[1][2] * m[0][0] + m[0][2] * m[1][0]," +
                        "b21, -m[2][1] * m[0][0] + m[0][1] * m[2][0], m[1][1] * m[0][0] - m[0][1] * m[1][0]" +
                        ") / det;" +
                        "}");

        transformer.injectFunction(
                "mat4 inverse(mat4 m) {" +
                        "float b00 = m[0][0] * m[1][1] - m[0][1] * m[1][0];" +
                        "float b01 = m[0][0] * m[1][2] - m[0][2] * m[1][0];" +
                        "float b02 = m[0][0] * m[1][3] - m[0][3] * m[1][0];" +
                        "float b03 = m[0][1] * m[1][2] - m[0][2] * m[1][1];" +
                        "float b04 = m[0][1] * m[1][3] - m[0][3] * m[1][1];" +
                        "float b05 = m[0][2] * m[1][3] - m[0][3] * m[1][2];" +
                        "float b06 = m[2][0] * m[3][1] - m[2][1] * m[3][0];" +
                        "float b07 = m[2][0] * m[3][2] - m[2][2] * m[3][0];" +
                        "float b08 = m[2][0] * m[3][3] - m[2][3] * m[3][0];" +
                        "float b09 = m[2][1] * m[3][2] - m[2][2] * m[3][1];" +
                        "float b10 = m[2][1] * m[3][3] - m[2][3] * m[3][1];" +
                        "float b11 = m[2][2] * m[3][3] - m[2][3] * m[3][2];" +
                        "float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;" +
                        "return mat4(" +
                        "m[1][1] * b11 - m[1][2] * b10 + m[1][3] * b09," +
                        "m[0][2] * b10 - m[0][1] * b11 - m[0][3] * b09," +
                        "m[3][1] * b05 - m[3][2] * b04 + m[3][3] * b03," +
                        "m[2][2] * b04 - m[2][1] * b05 - m[2][3] * b03," +
                        "m[1][2] * b08 - m[1][0] * b11 - m[1][3] * b07," +
                        "m[0][0] * b11 - m[0][2] * b08 + m[0][3] * b07," +
                        "m[3][2] * b02 - m[3][0] * b05 - m[3][3] * b01," +
                        "m[2][0] * b05 - m[2][2] * b02 + m[2][3] * b01," +
                        "m[1][0] * b10 - m[1][1] * b08 + m[1][3] * b06," +
                        "m[0][1] * b08 - m[0][0] * b10 - m[0][3] * b06," +
                        "m[3][0] * b04 - m[3][1] * b02 + m[3][3] * b00," +
                        "m[2][1] * b02 - m[2][0] * b04 - m[2][3] * b00," +
                        "m[1][1] * b07 - m[1][0] * b09 - m[1][2] * b06," +
                        "m[0][0] * b09 - m[0][1] * b07 + m[0][2] * b06," +
                        "m[3][1] * b01 - m[3][0] * b03 - m[3][2] * b00," +
                        "m[2][0] * b03 - m[2][1] * b01 + m[2][2] * b00" +
                        ") / det;" +
                        "}");
    }

    private static void renameLegacyTextureFunctions(Transformer transformer) {
        if (transformer.hasVariable("texture")) {
            renameSamplerKeepingCalls(transformer, "texture", "gtexture");
        }
        if (transformer.hasVariable("gcolor")) {
            transformer.rename("gcolor", "gtexture");
        }

        transformer.renameFunctionCall("texture2D", "texture");
        transformer.renameFunctionCall("texture3D", "texture");
        transformer.renameFunctionCall("texture2DLod", "textureLod");
        transformer.renameFunctionCall("texture3DLod", "textureLod");
        transformer.renameFunctionCall("texture2DProj", "textureProj");
        transformer.renameFunctionCall("texture3DProj", "textureProj");
        transformer.renameFunctionCall("texture2DGrad", "textureGrad");
        transformer.renameFunctionCall("texture2DGradARB", "textureGrad");
        transformer.renameFunctionCall("texture3DGrad", "textureGrad");
        transformer.renameFunctionCall("texelFetch2D", "texelFetch");
        transformer.renameFunctionCall("texelFetch3D", "texelFetch");
        transformer.renameFunctionCall("textureSize2D", "textureSize");

        transformer.renameAndWrapShadow("shadow2D", "texture");
        transformer.renameAndWrapShadow("shadow2DLod", "textureLod");
    }

    /**
     * Renames a sampler that shares its name with a built-in function, such as {@code texture(texture, uv)}. The
     * library's rename helpers either touch every occurrence or every occurrence inside a call, so the callee is
     * restored by hand afterwards.
     */
    private static void renameSamplerKeepingCalls(Transformer transformer, String from, String to) {
        transformer.rename(from, to);
        transformer.mutateTree(tree -> ParseTreeWalker.DEFAULT.walk(new GLSLParserBaseListener() {
            @Override
            public void visitTerminal(TerminalNode node) {
                if (node.getText().equals(to) && isCallee(node) && node.getSymbol() instanceof CommonToken token) {
                    token.setText(from);
                }
            }
        }, tree));
    }

    private static boolean isCallee(TerminalNode node) {
        if (!(node.getParent() instanceof GLSLParser.Variable_identifierContext identifier)) {
            return false;
        }

        ParseTree postfix = identifier.getParent() == null ? null : identifier.getParent().getParent();
        return postfix != null && postfix.getParent() instanceof GLSLParser.Postfix_expressionContext call
                && call.getChild(0) == postfix && call.function_call_parameters() != null;
    }

    private static void injectAlphaTestDiscard(Transformer transformer) {
        String output;

        if (transformer.containsCall("gl_FragData")) {
            output = "gl_FragData[0]";
        } else if (transformer.containsCall("gl_FragColor")) {
            output = "gl_FragColor";
        } else {
            return;
        }

        transformer.injectVariable("uniform float aurum_currentAlphaTest;");
        transformer.injectVariable("uniform int aurum_currentAlphaFunc;");
        transformer.injectFunction(
                "bool aurum_alphaTestPass(float a) {" +
                        "if (aurum_currentAlphaFunc == 7) return true;" +
                        "if (aurum_currentAlphaFunc == 0) return false;" +
                        "float qa = floor(a * 255.0 + 0.5);" +
                        "float qr = floor(aurum_currentAlphaTest * 255.0 + 0.5);" +
                        "if (aurum_currentAlphaFunc == 1) return qa < qr;" +
                        "if (aurum_currentAlphaFunc == 2) return qa == qr;" +
                        "if (aurum_currentAlphaFunc == 3) return qa <= qr;" +
                        "if (aurum_currentAlphaFunc == 4) return qa > qr;" +
                        "if (aurum_currentAlphaFunc == 5) return qa != qr;" +
                        "return qa >= qr;" +
                        "}");
        transformer.appendMain("if (!aurum_alphaTestPass(" + output + ".a)) discard;");
    }

    // does transformations that require cross-shader type data
    public static void transformGrouped(EnumMap<PatchShaderType, Transformer> trees) {
        PatchShaderType prevType = null;
        for (PatchShaderType type : pipeline) {
            if (trees.get(type) == null) {
                continue;
            }

            // if the current type has sources but the previous one doesn't, set the
            // previous one and continue
            if (prevType == null) {
                prevType = type;
                continue;
            }

            Transformer prevTransformer = trees.get(prevType);

            var outDec = new HashMap<>(prevTransformer.findQualifiers(GLSLLexer.OUT));
            outDec.putAll(prevTransformer.findQualifiers(GLSLLexer.VARYING));
            Transformer currentTransformer = trees.get(type);
            if (currentTransformer == null) {
                prevType = type;
                continue;
            }

            // MB: VARYING is intentionally not merged into inDec here (unlike outDec above). A
            // varying-to-varying pair on both stages (e.g. composite's near-universal
            // "varying vec4 texcoord;") is already fully self-consistent and needs no patching; only
            // merging on the outDec side is needed to recognize it as satisfying an "in"/"varying"
            // declaration in the next stage (the actual gap this fixed - entityColor declared
            // "varying" in the vertex stage but "in" in the geometry stage).
            var inDec = currentTransformer.findQualifiers(GLSLLexer.IN);
            for (String in : inDec.keySet()) {
                if (in.startsWith("gl_")) {
                    continue;
                }

                if (!outDec.containsKey(in)) {
                    if (!currentTransformer.containsCall(in)) {
                        continue;
                    }

                    String outDeclaration = ShaderPrinter.getFormattedShader(inDec.get(in).fully_specified_type())
                            + " " + in + ";";
                    prevTransformer.variable = null;
                    prevTransformer.injectVariable(outDeclaration.replaceFirst("in", "out"));

                    if (!prevTransformer.hasAssigment(in)) {
                        prevTransformer.initialize(inDec.get(in), in);
                    }
                    Aurum.LOGGER.warn("The in declaration '{}' in the {} shader is missing a corresponding out declaration in the previous stage {} and has been compatibility-patched. See debugging.md for more information.", in, type.name(), prevType.name());
                } else {
                    var outType = outDec.get(in).fully_specified_type().type_specifier().type_specifier_nonarray().children.getFirst();
                    var inType = inDec.get(in).fully_specified_type().type_specifier().type_specifier_nonarray().children.getFirst();

                    if (outDec.get(in).fully_specified_type().type_specifier().array_specifier() != null) {
                        continue;
                    }

                    if (inType.getText().equals(outType.getText())) {
                        if (!prevTransformer.hasAssigment(in)) {
                            prevTransformer.initialize(inDec.get(in), in);
                        }
                    } else {
                        int outComp = vecComponents(outType.getText());
                        int inComp = vecComponents(inType.getText());
                        if (outComp < 0 || inComp < 0) {
                            continue;
                        }

                        String temp = "aurum_template_" + in;
                        prevTransformer.rename(in, temp);
                        String newOut = ShaderPrinter.getFormattedShader(inDec.get(in).fully_specified_type())
                                + " " + in + ";";
                        prevTransformer.variable = null;
                        prevTransformer.injectVariable(newOut.replaceFirst("in", "out"));
                        String cast = outComp < inComp
                                ? in + " = " + inType.getText() + "(" + temp + ", vec4(0));"
                                : in + " = " + inType.getText() + "(" + temp + ");";
                        prevTransformer.appendMain(cast);

                        Aurum.LOGGER.warn("The out declaration '{}' in the {} shader has a different type {} than the corresponding in declaration of type {} in the following stage {} and has been compatibility-patched. See debugging.md for more information.", in, prevType.name(), outType.getText(), inType.getText(), type.name());
                    }
                }
            }

            prevType = type;
        }
    }

    private static int vecComponents(String type) {
        return switch (type) {
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> -1;
        };
    }
}
