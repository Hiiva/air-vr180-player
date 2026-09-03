fn main() {
    if let Err(error) = air_windows_player::run() {
        eprintln!("{error:#}");
        std::process::exit(1);
    }
}
