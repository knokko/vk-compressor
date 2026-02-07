#version 450

layout(location = 0) out vec2 textureCoordinates;

void main() {
	textureCoordinates = vec2(0.0, 0.0);
	if (gl_VertexIndex >= 1 && gl_VertexIndex <= 3) textureCoordinates.x = 1.0;
	if (gl_VertexIndex >= 2 && gl_VertexIndex <= 4) textureCoordinates.y = 1.0;
	gl_Position = vec4(textureCoordinates * 2.0 - vec2(1.0), 0.0, 1.0);
}
