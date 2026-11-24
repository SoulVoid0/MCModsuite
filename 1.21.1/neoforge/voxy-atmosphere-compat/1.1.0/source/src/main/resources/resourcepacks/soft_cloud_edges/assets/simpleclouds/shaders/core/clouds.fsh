#version 430

// Voxy Atmosphere Compat 1.1.0 override of simpleclouds:clouds (Simple Clouds 0.7.x).
// - Far clouds fade to transparent over the last part of the cloud distance instead of
//   blending to opaque fog colour (that produced a solid slab with a hard edge, whose
//   flat side wall showed depth stripes and holes against distant terrain).

#define EDGE_FADE_START 0.55
#define EDGE_FADE_END 0.97
#define FOG_TINT 0.45

uniform sampler2D BayerMatrixSampler;

uniform vec4 ColorModulator;
uniform float DitherScale;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec4 vertexColor;
in float fogDistance;

out vec4 fragColor;

void main()
{
    float fade = ColorModulator.a;
    float r = texture(BayerMatrixSampler, gl_FragCoord.xy * DitherScale).r;
    if (fade < r)
        discard;

    float edge = 1.0 - smoothstep(FogEnd * EDGE_FADE_START, FogEnd * EDGE_FADE_END, fogDistance);
    if (edge <= 0.004)
        discard;

    vec4 color = vertexColor * vec4(ColorModulator.rgb, 1.0);
    color.rgb = mix(color.rgb, FogColor.rgb, smoothstep(FogStart, FogEnd, fogDistance) * FOG_TINT);

    fragColor = vec4(color.rgb, edge);
}
