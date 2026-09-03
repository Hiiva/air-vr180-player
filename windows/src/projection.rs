use crate::renderer::{
    PROJECTION_EQUIRECT_VR180, PROJECTION_FISHEYE_VR190, PROJECTION_FISHEYE_VR200,
};

pub fn guess_from_name(name: &str) -> i32 {
    let normalized = name
        .to_lowercase()
        .rsplit_once('.')
        .map_or(name.to_lowercase(), |(stem, _)| stem.to_lowercase())
        .replace(['[', ']', '(', ')', '{', '}', ',', ';'], " ")
        .replace(['.', '_'], " ");

    for fov in [200, 190] {
        let spelled = format!(" {fov} ");
        let compact = format!("vr{fov}");
        let compact_reverse = format!("{fov}vr");
        let mkx = format!("mkx{fov}");
        let fisheye = format!(" {fov} fisheye ");
        if normalized.contains(&spelled)
            || normalized.contains(&fisheye)
            || normalized.contains(&compact)
            || normalized.contains(&compact_reverse)
            || normalized.contains(&mkx)
        {
            return if fov == 200 {
                PROJECTION_FISHEYE_VR200
            } else {
                PROJECTION_FISHEYE_VR190
            };
        }
    }
    PROJECTION_EQUIRECT_VR180
}
