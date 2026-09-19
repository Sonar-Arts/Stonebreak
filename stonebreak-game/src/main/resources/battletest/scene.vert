#version 330 core
layout(location=0) in vec3 position;
layout(location=1) in vec3 normal;
layout(location=2) in vec3 color;
layout(location=3) in float emission;
uniform mat4 projection;
uniform mat4 view;
uniform float time;
out vec3 worldPosition;
out vec3 worldNormal;
out vec3 baseColor;
out float glow;
void main(){
 vec3 p=position;
 if(emission>1.5){p.y=mod(position.y-time*.65,25.0)-1.0;p.x+=sin(time*.27+position.z*.08)*1.8;}
 worldPosition=p;worldNormal=normal;baseColor=color;glow=emission;
 gl_Position=projection*view*vec4(p,1.0);
}
