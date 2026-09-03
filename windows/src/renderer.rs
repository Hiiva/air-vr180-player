use glow::HasContext;
use std::cell::Cell;

pub const PROJECTION_EQUIRECT_VR180: i32 = 0;
pub const PROJECTION_FISHEYE_VR190: i32 = 1;
pub const PROJECTION_FISHEYE_VR200: i32 = 2;
const BASE_HORIZONTAL_FOV_DEGREES: f32 = 40.605_104;

const VERTEX_SHADER_SOURCE: &str = r#"
#version 330 core
layout(location = 0) in vec2 aPosition;
out vec2 vScreenUv;
void main() {
    vScreenUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"#;

const FRAGMENT_SHADER_SOURCE: &str = r#"
#version 330 core
precision highp float;
uniform sampler2D uTexture;
uniform mat3 uHeadRotation;
uniform vec2 uTanHalfFov;
uniform vec2 uViewOffset;
uniform int uStereoOutput;
uniform int uProjectionMode;
in vec2 vScreenUv;
out vec4 outColor;
const float PI = 3.141592653589793;
const float HALF_PI = 1.5707963267948966;
void main() {
    float eye = 0.0;
    vec2 eyeUv = vScreenUv;
    if (uStereoOutput == 1) {
        eye = step(0.5, vScreenUv.x);
        eyeUv.x = mix(vScreenUv.x * 2.0, (vScreenUv.x - 0.5) * 2.0, eye);
    }
    vec2 ndc = eyeUv * 2.0 - 1.0;
    vec3 headRay = normalize(vec3(ndc.x * uTanHalfFov.x, ndc.y * uTanHalfFov.y, -1.0));
    vec3 dir = normalize(uHeadRotation * headRay);
    float lon = atan(dir.x, -dir.z) + uViewOffset.x;
    float lat = clamp(asin(clamp(dir.y, -1.0, 1.0)) + uViewOffset.y, -HALF_PI, HALF_PI);
    float localU;
    float localV;
    if (uProjectionMode == 0) {
        if (abs(lon) > HALF_PI) {
            outColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        localU = lon / PI + 0.5;
        localV = 0.5 + lat / PI;
    } else {
        float maxAngle = HALF_PI;
        if (uProjectionMode == 1) maxAngle = radians(95.0);
        else if (uProjectionMode == 2) maxAngle = radians(100.0);
        vec3 lensDir = normalize(vec3(sin(lon) * cos(lat), sin(lat), -cos(lon) * cos(lat)));
        float theta = acos(clamp(-lensDir.z, -1.0, 1.0));
        if (theta > maxAngle) {
            outColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
        float radial = theta / maxAngle;
        float xyLength = length(lensDir.xy);
        vec2 radialDir = xyLength > 0.0001 ? lensDir.xy / xyLength : vec2(0.0, 0.0);
        localU = 0.5 + radialDir.x * radial * 0.5;
        localV = 0.5 + radialDir.y * radial * 0.5;
    }
    vec2 sourceUv = vec2(eye * 0.5 + localU * 0.5, localV);
    outColor = texture(uTexture, sourceUv);
}
"#;

pub struct Renderer {
    context: glow::Context,
    program: glow::Program,
    vertex_array: glow::VertexArray,
    #[allow(dead_code)]
    vertex_buffer: glow::Buffer,
    video_texture: glow::Texture,
    framebuffer: glow::Framebuffer,
    texture_size: Cell<(usize, usize)>,
    max_texture_size: usize,
    uniform_head_rotation: glow::UniformLocation,
    uniform_tan_half_fov: glow::UniformLocation,
    uniform_view_offset: glow::UniformLocation,
    uniform_texture: glow::UniformLocation,
    uniform_stereo_output: glow::UniformLocation,
    uniform_projection_mode: glow::UniformLocation,
}

impl Renderer {
    /// Create the VR render pipeline.
    ///
    /// # Safety
    ///
    /// The caller must ensure `context` is currently active and usable on this thread.
    pub unsafe fn new(context: glow::Context) -> anyhow::Result<Self> {
        let program = create_program(&context)?;
        // Do not impose an application-level resolution cap on the decoded
        // video. The texture is the source for the VR projection, so reducing
        // it here permanently throws away source detail before the final
        // 3840x1080 render. Clamp only to the actual OpenGL implementation
        // limit reported by the active GPU.
        let max_texture_size = context
            .get_parameter_i32(glow::MAX_TEXTURE_SIZE)
            .max(1) as usize;
        let vertex_array = context.create_vertex_array().map_err(anyhow::Error::msg)?;
        let vertex_buffer = context.create_buffer().map_err(anyhow::Error::msg)?;
        let vertices: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0];
        context.bind_vertex_array(Some(vertex_array));
        context.bind_buffer(glow::ARRAY_BUFFER, Some(vertex_buffer));
        context.buffer_data_u8_slice(
            glow::ARRAY_BUFFER,
            vertices.align_to::<u8>().1,
            glow::STATIC_DRAW,
        );
        context.enable_vertex_attrib_array(0);
        context.vertex_attrib_pointer_f32(0, 2, glow::FLOAT, false, 0, 0);
        context.bind_vertex_array(None);
        context.bind_buffer(glow::ARRAY_BUFFER, None);

        let video_texture = context.create_texture().map_err(anyhow::Error::msg)?;
        let framebuffer = context.create_framebuffer().map_err(anyhow::Error::msg)?;
        context.bind_texture(glow::TEXTURE_2D, Some(video_texture));
        context.tex_parameter_i32(
            glow::TEXTURE_2D,
            glow::TEXTURE_MIN_FILTER,
            // Video frames are replaced every render. Generating a full
            // mipmap pyramid for a 4K frame at 60 Hz causes visible stalls;
            // the warp samples a single level, so linear filtering is enough.
            glow::LINEAR as i32,
        );
        context.tex_parameter_i32(
            glow::TEXTURE_2D,
            glow::TEXTURE_MAG_FILTER,
            glow::LINEAR as i32,
        );
        context.tex_parameter_i32(
            glow::TEXTURE_2D,
            glow::TEXTURE_WRAP_S,
            glow::CLAMP_TO_EDGE as i32,
        );
        context.tex_parameter_i32(
            glow::TEXTURE_2D,
            glow::TEXTURE_WRAP_T,
            glow::CLAMP_TO_EDGE as i32,
        );
        context.tex_image_2d(
            glow::TEXTURE_2D,
            0,
            glow::RGBA8 as i32,
            1,
            1,
            0,
            glow::RGBA,
            glow::UNSIGNED_BYTE,
            None,
        );
        context.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
        context.framebuffer_texture_2d(
            glow::FRAMEBUFFER,
            glow::COLOR_ATTACHMENT0,
            glow::TEXTURE_2D,
            Some(video_texture),
            0,
        );
        context.bind_framebuffer(glow::FRAMEBUFFER, None);
        context.bind_texture(glow::TEXTURE_2D, None);

        Ok(Self {
            uniform_texture: context
                .get_uniform_location(program, "uTexture")
                .ok_or_else(|| anyhow::anyhow!("missing uTexture"))?,
            uniform_head_rotation: context
                .get_uniform_location(program, "uHeadRotation")
                .ok_or_else(|| anyhow::anyhow!("missing uHeadRotation"))?,
            uniform_tan_half_fov: context
                .get_uniform_location(program, "uTanHalfFov")
                .ok_or_else(|| anyhow::anyhow!("missing uTanHalfFov"))?,
            uniform_view_offset: context
                .get_uniform_location(program, "uViewOffset")
                .ok_or_else(|| anyhow::anyhow!("missing uViewOffset"))?,
            uniform_stereo_output: context
                .get_uniform_location(program, "uStereoOutput")
                .ok_or_else(|| anyhow::anyhow!("missing uStereoOutput"))?,
            uniform_projection_mode: context
                .get_uniform_location(program, "uProjectionMode")
                .ok_or_else(|| anyhow::anyhow!("missing uProjectionMode"))?,
            context,
            program,
            vertex_array,
            vertex_buffer,
            video_texture,
            framebuffer,
            texture_size: Cell::new((1, 1)),
            max_texture_size,
        })
    }

    pub fn resize_video_target(&self, width: usize, height: usize) {
        if width == 0 || height == 0 || (width, height) == self.texture_size.get() {
            return;
        }
        unsafe {
            self.context
                .bind_texture(glow::TEXTURE_2D, Some(self.video_texture));
            self.context.tex_image_2d(
                glow::TEXTURE_2D,
                0,
                glow::RGBA8 as i32,
                width as i32,
                height as i32,
                0,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                None,
            );
            self.context.bind_texture(glow::TEXTURE_2D, None);
        }
        self.texture_size.set((width, height));
    }

    #[allow(clippy::too_many_arguments)]
    pub fn draw(
        &self,
        mpv: &crate::mpv::Mpv,
        width: usize,
        height: usize,
        head_rotation: &[f32; 9],
        zoom: f32,
        view_offset: [f32; 2],
        projection_mode: i32,
        stereo_override: Option<bool>,
    ) -> anyhow::Result<()> {
        let stereo = stereo_override.unwrap_or_else(|| should_render_stereo(width, height));
        let max_target_width = if stereo {
            (width / 2).max(1)
        } else {
            width.max(1)
        };
        let max_target_height = height.max(1);
        let (source_width, source_height) = mpv
            .video_dimensions()
            .unwrap_or((max_target_width, max_target_height));
        let scale = (self.max_texture_size as f32 / source_width as f32)
            .min(self.max_texture_size as f32 / source_height as f32)
            .min(1.0);
        let target_width = ((source_width as f32 * scale) as usize).max(1);
        let target_height = ((source_height as f32 * scale) as usize).max(1);
        unsafe {
            self.resize_video_target(target_width, target_height);
            self.context
                .bind_framebuffer(glow::FRAMEBUFFER, Some(self.framebuffer));
            self.context
                .viewport(0, 0, target_width as i32, target_height as i32);
            self.context.clear_color(0.0, 0.0, 0.0, 1.0);
            self.context.clear(glow::COLOR_BUFFER_BIT);
            mpv.render(self.framebuffer.0.get(), target_width, target_height, true)?;

            self.context.bind_framebuffer(glow::FRAMEBUFFER, None);
            self.context.active_texture(glow::TEXTURE0);
            self.context
                .bind_texture(glow::TEXTURE_2D, Some(self.video_texture));
            self.context.viewport(0, 0, width as i32, height as i32);
            self.context.clear_color(0.0, 0.0, 0.0, 1.0);
            self.context.clear(glow::COLOR_BUFFER_BIT);
            self.context.use_program(Some(self.program));
            self.context.disable(glow::DEPTH_TEST);
            self.context.disable(glow::CULL_FACE);
            self.context.uniform_1_i32(Some(&self.uniform_texture), 0);
            self.context.active_texture(glow::TEXTURE0);
            self.context
                .bind_texture(glow::TEXTURE_2D, Some(self.video_texture));
            self.context
                .uniform_1_i32(Some(&self.uniform_projection_mode), projection_mode);
            self.context.uniform_matrix_3_f32_slice(
                Some(&self.uniform_head_rotation),
                false,
                head_rotation,
            );
            let eye_aspect = if stereo {
                (width as f32 * 0.5) / height.max(1) as f32
            } else {
                width as f32 / height.max(1) as f32
            };
            let tan_half_horizontal =
                (BASE_HORIZONTAL_FOV_DEGREES * 0.5).to_radians().tan() / zoom.clamp(0.60, 1.80);
            let tan_half_vertical = tan_half_horizontal / eye_aspect.max(0.1);
            self.context.uniform_2_f32_slice(
                Some(&self.uniform_tan_half_fov),
                &[tan_half_horizontal, tan_half_vertical],
            );
            self.context
                .uniform_2_f32_slice(Some(&self.uniform_view_offset), &view_offset);
            self.context
                .uniform_1_i32(Some(&self.uniform_stereo_output), stereo as i32);
            self.context.bind_vertex_array(Some(self.vertex_array));
            self.context.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            self.context.bind_vertex_array(None);
        }
        Ok(())
    }
}

pub fn should_render_stereo(width: usize, height: usize) -> bool {
    height > 0 && (width >= 3000 || width as f32 / height as f32 >= 2.4)
}

unsafe fn create_program(context: &glow::Context) -> anyhow::Result<glow::Program> {
    let vertex_shader = compile_shader(context, glow::VERTEX_SHADER, VERTEX_SHADER_SOURCE)?;
    let fragment_shader = compile_shader(context, glow::FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE)?;
    let program = context.create_program().map_err(anyhow::Error::msg)?;
    context.attach_shader(program, vertex_shader);
    context.attach_shader(program, fragment_shader);
    context.link_program(program);
    if !context.get_program_link_status(program) {
        let message = context.get_program_info_log(program);
        anyhow::bail!("shader link failed: {message}");
    }
    context.delete_shader(vertex_shader);
    context.delete_shader(fragment_shader);
    Ok(program)
}

unsafe fn compile_shader(
    context: &glow::Context,
    kind: u32,
    source: &str,
) -> anyhow::Result<glow::Shader> {
    let shader = context.create_shader(kind).map_err(anyhow::Error::msg)?;
    context.shader_source(shader, source);
    context.compile_shader(shader);
    if !context.get_shader_compile_status(shader) {
        let message = context.get_shader_info_log(shader);
        anyhow::bail!("shader compile failed: {message}");
    }
    Ok(shader)
}
