#version 330 core
out vec2 screen;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);screen=p*2.0-1.0;gl_Position=vec4(screen,1.0,1.0);}
