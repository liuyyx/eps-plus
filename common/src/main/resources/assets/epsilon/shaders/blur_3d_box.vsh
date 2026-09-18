#version 410 core

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
