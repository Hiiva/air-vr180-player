use libloading::{Library, Symbol};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::path::Path;

const MPV_FORMAT_STRING: i64 = 1;
const MPV_FORMAT_FLAG: i64 = 3;
const MPV_FORMAT_DOUBLE: i64 = 5;
const MPV_FORMAT_INT64: i64 = 4;
const MPV_RENDER_PARAM_API_TYPE: c_int = 1;
const MPV_RENDER_PARAM_OPENGL_INIT_PARAMS: c_int = 2;
const MPV_RENDER_PARAM_OPENGL_FBO: c_int = 3;
const MPV_RENDER_PARAM_FLIP_Y: c_int = 4;
const MPV_RENDER_API_TYPE_OPENGL: &CStr = c"opengl";

pub type GetProcAddress = unsafe extern "system" fn(*mut c_void, *const c_char) -> *mut c_void;
pub type RenderUpdateCallback = unsafe extern "system" fn(*mut c_void);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct MpvOpenGlInitParams {
    pub get_proc_address: Option<GetProcAddress>,
    pub get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct MpvOpenGLFbo {
    pub fbo: i32,
    pub width: i32,
    pub height: i32,
    pub internal_format: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct MpvRenderParam {
    pub kind: c_int,
    pub data: *mut c_void,
}

unsafe extern "system" fn get_proc_address_ffi(
    ctx: *mut c_void,
    name: *const c_char,
) -> *mut c_void {
    unsafe {
        let loader = &*(ctx.cast::<Box<dyn Fn(&CStr) -> *mut c_void>>());
        loader(CStr::from_ptr(name))
    }
}

unsafe extern "system" fn render_update(_user_data: *mut c_void) {}

unsafe fn set_option(
    set_string: &Symbol<unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int>,
    handle: *mut c_void,
    name: &CStr,
    value: &CStr,
) {
    unsafe {
        if set_string(handle, name.as_ptr(), value.as_ptr()) != 0 {
            eprintln!(
                "[mpv] option rejected: {}={}",
                name.to_string_lossy(),
                value.to_string_lossy()
            );
        }
    }
}

pub struct Mpv {
    library: Library,
    handle: *mut c_void,
    render_context: *mut c_void,
}

unsafe impl Send for Mpv {}

impl Mpv {
    pub fn new(loader: Box<dyn Fn(&CStr) -> *mut c_void>) -> anyhow::Result<Self> {
        let library = unsafe { Library::new("libmpv-2.dll") }?;
        let handle = unsafe {
            let create: Symbol<unsafe extern "C" fn() -> *mut c_void> =
                library.get(b"mpv_create\0")?;
            let handle = create();
            if handle.is_null() {
                anyhow::bail!("mpv_create failed");
            }
            handle
        };

        unsafe {
            let set_string: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
            > = library.get(b"mpv_set_option_string\0")?;
            set_option(&set_string, handle, c"terminal", c"no");
            set_option(&set_string, handle, c"vo", c"libmpv");
            set_option(&set_string, handle, c"hwdec", c"auto-safe");
            set_option(&set_string, handle, c"keep-open", c"always");
            set_option(&set_string, handle, c"pause", c"no");
            set_option(&set_string, handle, c"mute", c"yes");
            set_option(&set_string, handle, c"cache", c"yes");
            set_option(&set_string, handle, c"demuxer-max-bytes", c"256MiB");
            // Keep the render path close to Android's hardware-decoder path.
            // The high-quality mpv profile enables several expensive scaling
            // and debanding passes that make 4K SBS playback stutter on the
            // glasses output.
            set_option(&set_string, handle, c"profile", c"fast");
            set_option(&set_string, handle, c"video-sync", c"display-resample");
            set_option(&set_string, handle, c"interpolation", c"no");
            set_option(&set_string, handle, c"scale", c"bilinear");
            set_option(&set_string, handle, c"cscale", c"bilinear");
            set_option(&set_string, handle, c"dscale", c"bilinear");
            set_option(&set_string, handle, c"deband", c"no");
            set_option(&set_string, handle, c"sigmoid-upscaling", c"no");

            let initialize: Symbol<unsafe extern "C" fn(*mut c_void) -> c_int> =
                library.get(b"mpv_initialize\0")?;
            if initialize(handle) != 0 {
                anyhow::bail!("mpv_initialize failed");
            }
        }

        let init_params = MpvOpenGlInitParams {
            get_proc_address: Some(get_proc_address_ffi),
            get_proc_address_ctx: Box::into_raw(Box::new(loader)).cast(),
        };
        let api_type = MPV_RENDER_API_TYPE_OPENGL.as_ptr();
        let mut params = [
            MpvRenderParam {
                kind: MPV_RENDER_PARAM_API_TYPE,
                data: api_type.cast::<c_void>().cast_mut(),
            },
            MpvRenderParam {
                kind: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,
                data: (&raw const init_params).cast::<c_void>().cast_mut(),
            },
            MpvRenderParam {
                kind: 0,
                data: std::ptr::null_mut(),
            },
        ];

        let render_context = unsafe {
            let create: Symbol<
                unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *mut MpvRenderParam) -> c_int,
            > = library.get(b"mpv_render_context_create\0")?;
            let mut context: *mut c_void = std::ptr::null_mut();
            let result = create(&raw mut context, handle, params.as_mut_ptr());
            if result != 0 || context.is_null() {
                let error_text = library
                    .get::<Symbol<unsafe extern "C" fn(c_int) -> *const c_char>>(
                        b"mpv_error_string\0",
                    )
                    .ok()
                    .map(|error_string| {
                        std::ffi::CStr::from_ptr(error_string(result))
                            .to_string_lossy()
                            .into_owned()
                    })
                    .unwrap_or_default();
                anyhow::bail!("mpv_render_context_create failed: {result} {error_text}");
            }
            context
        };

        unsafe {
            let set_callback: Symbol<
                unsafe extern "C" fn(*mut c_void, Option<RenderUpdateCallback>, *mut c_void),
            > = library.get(b"mpv_render_context_set_update_callback\0")?;
            set_callback(render_context, Some(render_update), std::ptr::null_mut());
        }

        Ok(Self {
            library,
            handle,
            render_context,
        })
    }

    fn command(&self, arguments: &[&str]) -> anyhow::Result<()> {
        let values: Vec<CString> = arguments
            .iter()
            .map(|value| CString::new(*value))
            .collect::<Result<_, _>>()?;
        let mut pointers: Vec<*mut c_char> = values
            .iter()
            .map(|value| value.as_ptr().cast_mut())
            .collect();
        pointers.push(std::ptr::null_mut());
        unsafe {
            let command: Symbol<unsafe extern "C" fn(*mut c_void, *mut *mut c_char) -> c_int> =
                self.library.get(b"mpv_command\0")?;
            if command(self.handle, pointers.as_mut_ptr()) != 0 {
                anyhow::bail!("mpv command failed: {}", arguments.join(" "));
            }
        }
        Ok(())
    }

    pub fn load_file(&self, path: &str) -> anyhow::Result<()> {
        self.command(&["loadfile", path, "replace"])
    }

    pub fn play_pause(&self) -> anyhow::Result<()> {
        self.command(&["cycle", "pause"])
    }

    pub fn seek_relative(&self, seconds: f64) -> anyhow::Result<()> {
        self.command(&["seek", &seconds.to_string(), "relative"])
    }

    pub fn seek_absolute(&self, seconds: f64) -> anyhow::Result<()> {
        self.command(&["seek", &seconds.to_string(), "absolute"])
    }

    pub fn stop(&self) -> anyhow::Result<()> {
        self.command(&["stop"])
    }

    pub fn set_paused(&self, paused: bool) -> anyhow::Result<()> {
        let value = paused as c_int;
        unsafe {
            let setter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_set_property\0")?;
            let result = setter(
                self.handle,
                c"pause".as_ptr(),
                MPV_FORMAT_FLAG,
                (&raw const value).cast::<c_void>().cast_mut(),
            );
            if result != 0 {
                anyhow::bail!("could not set pause");
            }
        }
        Ok(())
    }

    pub fn set_muted(&self, muted: bool) -> anyhow::Result<()> {
        let value = muted as c_int;
        unsafe {
            let setter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_set_property\0")?;
            let result = setter(
                self.handle,
                c"mute".as_ptr(),
                MPV_FORMAT_FLAG,
                (&raw const value).cast::<c_void>().cast_mut(),
            );
            if result != 0 {
                anyhow::bail!("could not set mute");
            }
        }
        Ok(())
    }

    pub fn set_volume(&self, volume: i64) -> anyhow::Result<()> {
        let value = volume as f64;
        unsafe {
            let setter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_set_property\0")?;
            let result = setter(
                self.handle,
                c"volume".as_ptr(),
                MPV_FORMAT_DOUBLE,
                (&raw const value).cast::<c_void>().cast_mut(),
            );
            if result != 0 {
                anyhow::bail!("could not set volume");
            }
        }
        Ok(())
    }

    pub fn set_speed(&self, speed: f64) -> anyhow::Result<()> {
        let value = speed;
        unsafe {
            let setter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_set_property\0")?;
            let result = setter(
                self.handle,
                c"speed".as_ptr(),
                MPV_FORMAT_DOUBLE,
                (&raw const value).cast::<c_void>().cast_mut(),
            );
            if result != 0 {
                anyhow::bail!("could not set speed");
            }
        }
        Ok(())
    }

    fn property_f64(&self, name: &CStr) -> Option<f64> {
        let mut value = f64::default();
        unsafe {
            let getter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_get_property\0").ok()?;
            if getter(
                self.handle,
                name.as_ptr(),
                MPV_FORMAT_DOUBLE,
                (&raw mut value).cast(),
            ) == 0
            {
                Some(value)
            } else {
                None
            }
        }
    }

    fn property_i64(&self, name: &CStr) -> Option<i64> {
        let mut value = i64::default();
        unsafe {
            let getter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_get_property\0").ok()?;
            if getter(
                self.handle,
                name.as_ptr(),
                MPV_FORMAT_INT64,
                (&raw mut value).cast(),
            ) == 0
            {
                Some(value)
            } else {
                None
            }
        }
    }

    pub fn position_seconds(&self) -> Option<f64> {
        self.property_f64(c"time-pos")
    }

    pub fn duration_seconds(&self) -> Option<f64> {
        self.property_f64(c"duration")
    }

    pub fn is_seekable(&self) -> bool {
        self.property_flag(c"seekable").unwrap_or(false)
    }

    fn property_flag(&self, name: &CStr) -> Option<bool> {
        let mut value: c_int = 0;
        unsafe {
            let getter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_get_property\0").ok()?;
            if getter(
                self.handle,
                name.as_ptr(),
                MPV_FORMAT_FLAG,
                (&raw mut value).cast(),
            ) == 0
            {
                Some(value != 0)
            } else {
                None
            }
        }
    }

    pub fn is_paused(&self) -> bool {
        let mut value: c_int = 0;
        unsafe {
            let Ok(getter): Result<
                Symbol<unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int>,
                _,
            > = self.library.get(b"mpv_get_property\0") else {
                return false;
            };
            getter(
                self.handle,
                c"pause".as_ptr(),
                MPV_FORMAT_FLAG,
                (&raw mut value).cast(),
            ) == 0
                && value != 0
        }
    }

    pub fn media_title(&self) -> Option<String> {
        self.property_string(c"media-title")
    }

    pub fn path(&self) -> Option<String> {
        self.property_string(c"path")
    }

    pub fn speed(&self) -> f64 {
        self.property_f64(c"speed").unwrap_or(1.0)
    }

    pub fn volume(&self) -> i64 {
        self.property_f64(c"volume")
            .map_or(100, |value| value.round() as i64)
    }

    pub fn is_muted(&self) -> bool {
        let mut value: c_int = 0;
        unsafe {
            let Ok(getter): Result<
                Symbol<unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int>,
                _,
            > = self.library.get(b"mpv_get_property\0") else {
                return false;
            };
            getter(
                self.handle,
                c"mute".as_ptr(),
                MPV_FORMAT_FLAG,
                (&raw mut value).cast(),
            ) == 0
                && value != 0
        }
    }

    pub fn has_media(&self) -> bool {
        self.path().is_some_and(|path| !path.is_empty())
    }

    pub fn video_dimensions(&self) -> Option<(usize, usize)> {
        let width = self.property_i64(c"width")?;
        let height = self.property_i64(c"height")?;
        if width > 0 && height > 0 {
            Some((width as usize, height as usize))
        } else {
            None
        }
    }

    fn property_string(&self, name: &CStr) -> Option<String> {
        let mut value: *mut c_char = std::ptr::null_mut();
        unsafe {
            let getter: Symbol<
                unsafe extern "C" fn(*mut c_void, *const c_char, i64, *mut c_void) -> c_int,
            > = self.library.get(b"mpv_get_property\0").ok()?;
            if getter(
                self.handle,
                name.as_ptr(),
                MPV_FORMAT_STRING,
                (&raw mut value).cast(),
            ) != 0
                || value.is_null()
            {
                return None;
            }
            let text = CStr::from_ptr(value).to_string_lossy().into_owned();
            if let Ok(free) = self
                .library
                .get::<Symbol<unsafe extern "C" fn(*mut c_char)>>(b"mpv_free\0")
            {
                free(value);
            }
            Some(text)
        }
    }

    pub fn render(
        &self,
        framebuffer: u32,
        width: usize,
        height: usize,
        flip_y: bool,
    ) -> anyhow::Result<()> {
        let fbo = MpvOpenGLFbo {
            fbo: framebuffer as i32,
            width: width as i32,
            height: height as i32,
            internal_format: 0,
        };
        let flip = flip_y as c_int;
        let mut params = [
            MpvRenderParam {
                kind: MPV_RENDER_PARAM_OPENGL_FBO,
                data: (&raw const fbo).cast::<c_void>().cast_mut(),
            },
            MpvRenderParam {
                kind: MPV_RENDER_PARAM_FLIP_Y,
                data: (&raw const flip).cast::<c_void>().cast_mut(),
            },
            MpvRenderParam {
                kind: 0,
                data: std::ptr::null_mut(),
            },
        ];
        unsafe {
            let render: Symbol<unsafe extern "C" fn(*mut c_void, *mut MpvRenderParam) -> c_int> =
                self.library.get(b"mpv_render_context_render\0")?;
            let result = render(self.render_context, params.as_mut_ptr());
            if result != 0 {
                anyhow::bail!("mpv_render_context_render failed: {result}");
            }
        }
        Ok(())
    }

    /// Tell libmpv that the rendered frame has been presented. This is
    /// required for display-resample video sync to pace frames against the
    /// actual swap interval instead of guessing from command time.
    pub fn report_swap(&self) {
        unsafe {
            if let Ok(report) = self
                .library
                .get::<Symbol<unsafe extern "C" fn(*mut c_void) -> c_int>>(
                    b"mpv_render_context_report_swap\0",
                )
            {
                let _ = report(self.render_context);
            }
        }
    }
}

impl Drop for Mpv {
    fn drop(&mut self) {
        unsafe {
            if let Ok(free_context) = self
                .library
                .get::<Symbol<unsafe extern "C" fn(*mut c_void)>>(b"mpv_render_context_free\0")
            {
                free_context(self.render_context);
            }
            if let Ok(destroy) = self
                .library
                .get::<Symbol<unsafe extern "C" fn(*mut c_void)>>(b"mpv_terminate_destroy\0")
            {
                destroy(self.handle);
            }
        }
    }
}

pub fn canonical_media_path(path: &Path) -> String {
    path.to_string_lossy().replace('/', "\\")
}
