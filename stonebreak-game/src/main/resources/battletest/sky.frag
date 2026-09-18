#version 330 core
in vec2 screen;
uniform mat4 inverseProjection;
uniform mat4 inverseView;
uniform float time;
out vec4 fragColor;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
void main(){
 vec4 q=inverseProjection*vec4(screen,1,1);
 vec3 d=normalize(mat3(inverseView)*(q.xyz/q.w));
 float elevation=max(d.y,0.0);
 vec3 c=mix(vec3(.24,.38,.48),vec3(.018,.04,.10),pow(elevation,.45));
 float az=atan(d.z,d.x);
 // Animated aurora curtains are anchored in world direction, so camera rotation cannot move them.
 float band=.34+.10*sin(az*3.0+time*.025)+.04*sin(az*7.0-time*.04);
 float aurora=exp(-pow((d.y-band)/.10,2.0))*(.55+.45*sin(az*36.0+sin(az*11.0)+time*.13));
 aurora*=smoothstep(.02,.2,d.y);
 c+=aurora*mix(vec3(.04,.29,.23),vec3(.16,.09,.30),smoothstep(band-.08,band+.12,d.y));
 vec2 starUv=vec2(az,d.y)*vec2(220,180);
 float star=step(.994,hash(floor(starUv)))*pow(max(0.0,1.0-length(fract(starUv)-.5)*2.0),8.0);
 c+=star*smoothstep(.2,.8,d.y)*.75;
 vec3 moonDir=normalize(vec3(-.5,.5,-.7));
 float moon=dot(d,moonDir);
 c+=vec3(.7,.83,.9)*smoothstep(.9993,.9995,moon);
 c+=vec3(.03,.06,.08)*pow(max(moon,0.0),80.0);
 fragColor=vec4(c,1.0);
}
