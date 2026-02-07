#version 450

layout(location = 0) in uvec2 inSize;

layout(location = 0) out uvec2 outSize;
layout(location = 1) out vec2 outTextureCoordinates;

void main() {
	vec2 textureCoordinates = vec2(0.0, 0.0);
	if (gl_VertexIndex >= 1 && gl_VertexIndex <= 3) textureCoordinates.x = 1.0;
	if (gl_VertexIndex >= 2 && gl_VertexIndex <= 4) textureCoordinates.y = 1.0;
	outSize = inSize;
	outTextureCoordinates = textureCoordinates;
	gl_Position = vec4(vec2(-1.0, -1.0) + 2.0 * textureCoordinates, 0.0, 1.0);
}
