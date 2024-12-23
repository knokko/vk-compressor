#version 450

layout(location = 0) in vec2 textureCoordinates;
layout(location = 1) in flat uint imageIndex;

layout(set = 0, binding = 0) readonly buffer ImageBuffer {
	uint kimBuffer[];
};

layout(location = 0) out vec4 outColor;

#include "kim2.glsl"

defineSampleKim2Float(kimBuffer)

void main() {
	outColor = sampleKim2(imageIndex, textureCoordinates);
}
