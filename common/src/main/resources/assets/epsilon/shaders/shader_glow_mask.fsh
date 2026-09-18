#version 410 core

uniform sampler2D InputSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(InputSampler, texCoord);
}
