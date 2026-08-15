#version 330 core
    #include "/shaders/loadertest/middle.glsl"
void main() { gl_FragColor = vec4(middleValue()); }
