#version 330 core
in vec3 worldPosition;
in vec3 worldNormal;
in vec3 baseColor;
in float glow;
uniform vec3 camera;
uniform float time;
out vec4 fragColor;
void main(){
 if(glow>1.5 && worldPosition.y < .15) discard;
 vec3 n=normalize(worldNormal);if(!gl_FrontFacing)n=-n;
 vec3 light=normalize(vec3(-.35,.8,.45));
 float diffuse=max(dot(n,light),0.0);
 float rim=pow(1.0-max(dot(n,normalize(camera-worldPosition)),0.0),3.0);
 vec3 c=baseColor*(.56+.44*diffuse)+vec3(.04,.08,.10)*rim;
 c=mix(c,baseColor*(1.05+.10*sin(time*1.3+worldPosition.z*.15)),clamp(glow,0.0,1.0));
 float fog=smoothstep(48.0,250.0,length(camera-worldPosition));
 c=mix(c,vec3(.20,.33,.43),fog*.93);
 fragColor=vec4(c,1.0);
}
