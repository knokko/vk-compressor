#version 450

layout(location = 0) in vec2 textureCoordinates;

layout(set = 0, binding = 0) uniform sampler2D image;

layout(push_constant) uniform PushConstants {
	bool signed;
};

layout(location = 0) out vec4 outColor;

void main() {
	vec4 sampledColor = texture(image, textureCoordinates);
	if (signed) {
		float sampledValue = sampledColor.r;
		outColor = vec4(max(0.0, sampledValue), max(0.0, -sampledValue), abs(sampledValue), 1.0);
	} else {
		outColor = sampledColor;
	}
}
