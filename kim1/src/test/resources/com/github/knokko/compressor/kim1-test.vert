#version 450

layout(location = 0) in vec2 inPosition;
layout(location = 1) in uvec2 inSize;
layout(location = 2) in uint inImageOffset;

layout(location = 0) out uvec2 outSize;
layout(location = 1) out vec2 outTextureCoordinates;
layout(location = 2) out uint outImageOffset;

void main() {
	vec2 textureCoordinates = vec2(0.0, 0.0);
	if (gl_VertexIndex >= 1 && gl_VertexIndex <= 3) textureCoordinates.x = 1.0;
	if (gl_VertexIndex >= 2 && gl_VertexIndex <= 4) textureCoordinates.y = 1.0;
	outSize = inSize;
	outTextureCoordinates = textureCoordinates;
	outImageOffset = inImageOffset;
	gl_Position = vec4(inPosition + textureCoordinates * inSize * 0.01, 0.0, 1.0);
}
