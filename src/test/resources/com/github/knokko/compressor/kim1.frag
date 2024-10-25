#version 450

layout(constant_id = 0) const int KIM_BUFFER_SIZE = 1;

layout(location = 0) in vec2 textureCoordinates;

layout(push_constant) uniform PushConstants {
	layout(offset = 8) int imageIndex;
};

layout(set = 0, binding = 0) readonly buffer ImageBuffer {
	uint kimBuffer[KIM_BUFFER_SIZE];
};

layout(location = 0) out vec4 outColor;

#include "kim1.glsl"

defineReadInt(kimBuffer)

defineSampleKimFloat(kimBuffer)

defineSampleKimInt(kimBuffer)

void main() {
	outColor = sampleKim(imageIndex, textureCoordinates);
}
