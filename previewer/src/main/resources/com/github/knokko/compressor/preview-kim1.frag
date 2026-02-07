#version 450

layout(location = 0) in flat uvec2 size;
layout(location = 1) in vec2 textureCoordinates;

layout(set = 0, binding = 0) readonly buffer ImageBuffer {
	uint kimBuffer[];
};

layout(location = 0) out vec4 outColor;

#include "../../../../../../../../kim1/src/test/resources/com/github/knokko/compressor/kim1.glsl"

defineReadInt(kimBuffer)

defineSampleKimInt(kimBuffer)

void main() {
	outColor = sampleKim(0, uvec2(textureCoordinates * size));
}
