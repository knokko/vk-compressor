#version 450

layout(constant_id = 0) const int KIM_BUFFER_SIZE = 1;

layout(location = 0) in flat uvec2 size;
layout(location = 1) in vec2 textureCoordinates;
layout(location = 2) in flat uint imageOffset;

layout(set = 0, binding = 0) readonly buffer ImageBuffer {
	uint kimBuffer[KIM_BUFFER_SIZE];
};

layout(location = 0) out vec4 outColor;

#include "kim1.glsl"

defineReadInt(kimBuffer)

defineSampleKimInt(kimBuffer)

void main() {
	outColor = sampleKim(imageOffset, uvec2(textureCoordinates * size));
}
