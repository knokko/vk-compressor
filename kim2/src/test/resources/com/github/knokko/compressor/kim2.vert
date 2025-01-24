#version 450

layout(location = 0) in vec2 inPosition;
layout(location = 1) in uint inImageOffset;

layout(set = 0, binding = 0) readonly buffer ImageBuffer {
	uint kimBuffer[];
};

layout(location = 0) out vec2 outTextureCoordinates;
layout(location = 1) out uint outImageOffset;

#include "kim2.glsl"

void main() {
	vec2 textureCoordinates = vec2(0.0, 0.0);
	if (gl_VertexIndex >= 1 && gl_VertexIndex <= 3) textureCoordinates.x = 1.0;
	if (gl_VertexIndex >= 2 && gl_VertexIndex <= 4) textureCoordinates.y = 1.0;
	outTextureCoordinates = textureCoordinates;
	outImageOffset = inImageOffset;
	gl_Position = vec4(inPosition + textureCoordinates, 0.0, 1.0);
}
