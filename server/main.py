from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, StreamingResponse

load_dotenv()


def parse_positive_int_env(name: str, default: int, minimum: int = 1) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return max(minimum, int(raw))
    except ValueError:
        return default


def parse_bool_env(name: str, default: bool = False) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


DEFAULT_SOURCES = [Path("videos")]
DEFAULT_EXTENSIONS = {".mp4", ".mkv", ".mov"}
CACHE_DIR = Path(os.getenv("CACHE_DIR", "cache"))
THUMBNAIL_DIR = CACHE_DIR / "thumbnails"
METADATA_DIR = CACHE_DIR / "metadata"
LIBRARY_INDEX_FILE = CACHE_DIR / "library_index.json"
DELETION_FLAGS_FILE = CACHE_DIR / "deletion_flags.txt"
THUMBNAIL_FAILURE_DIR = CACHE_DIR / "thumbnail_failures"
THUMBNAIL_LOCKS_LOCK = threading.Lock()
THUMBNAIL_GENERATION_LOCKS: dict[str, threading.Lock] = {}
DELETION_FLAGS_LOCK = threading.Lock()
THUMBNAIL_CACHE_VERSION = "left-eye-v1"
THUMBNAIL_KEY_PATTERN = re.compile(r"^[0-9a-f]{32}$")
STREAM_CHUNK_SIZE = parse_positive_int_env("STREAM_CHUNK_SIZE_BYTES", 8 * 1024 * 1024, 64 * 1024)
STREAM_INITIAL_CHUNK_SIZE = parse_positive_int_env(
    "STREAM_INITIAL_CHUNK_SIZE_BYTES", 256 * 1024, 16 * 1024
)
VIDEO_PATH_CACHE_TTL_SECONDS = parse_positive_int_env("VIDEO_PATH_CACHE_TTL_SECONDS", 0, 0)
LIBRARY_REFRESH_INTERVAL_SECONDS = parse_positive_int_env("LIBRARY_REFRESH_INTERVAL_SECONDS", 0, 0)
VIDEO_COPY_SETTLE_SECONDS = parse_positive_int_env("VIDEO_COPY_SETTLE_SECONDS", 30, 0)
VIDEO_PATH_CACHE_LOCK = threading.Lock()
VIDEO_PATH_CACHE: dict[str, Path] = {}
VIDEO_PATH_CACHE_LOADED_AT = 0.0
LIBRARY_INDEX_LOCK = threading.Lock()
LIBRARY_SNAPSHOT_LOCK = threading.Lock()
LIBRARY_SNAPSHOT_ENTRIES: dict[str, LibraryIndexEntry] | None = None
LIBRARY_SNAPSHOT_SCANNED_AT = 0.0
LIBRARY_SNAPSHOT_LOADED_AT = 0.0
LIBRARY_REFRESH_LOCK = threading.Lock()
LIBRARY_REFRESH_THREAD: threading.Thread | None = None
LIBRARY_REFRESH_STARTED_AT = 0.0
LIBRARY_REFRESH_FINISHED_AT = 0.0
LIBRARY_REFRESH_LAST_ERROR = ""

if os.name == "nt":
    import ctypes
    import msvcrt

    _KERNEL32 = ctypes.WinDLL("kernel32", use_last_error=True)
    _GENERIC_READ = 0x80000000
    _FILE_SHARE_READ = 0x00000001
    _FILE_SHARE_WRITE = 0x00000002
    _FILE_SHARE_DELETE = 0x00000004
    _OPEN_EXISTING = 3
    _FILE_ATTRIBUTE_NORMAL = 0x00000080
    _INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    _KERNEL32.CreateFileW.argtypes = [
        ctypes.c_wchar_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_void_p,
    ]
    _KERNEL32.CreateFileW.restype = ctypes.c_void_p


@dataclass(frozen=True)
class VideoFile:
    id: str
    cache_key: str
    title: str
    display_name: str
    subfolder: str
    path: Path
    size: int
    modified: float
    duration_ms: int
    flagged_for_deletion: bool


@dataclass(frozen=True)
class LibraryIndexEntry:
    id: str
    cache_key: str
    title: str
    display_name: str
    subfolder: str
    path_text: str
    size: int
    modified: float
    modified_ns: int
    duration_ms: int


app = FastAPI(title="Air VR180 Video Server")


def get_api_key() -> str:
    api_key = os.getenv("API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("API_KEY must be set in server/.env or the environment")
    return api_key


def parse_sources() -> list[Path]:
    raw = os.getenv("SOURCES", "").strip()
    if not raw:
        return DEFAULT_SOURCES
    return [Path(item.strip()) for item in raw.split(";") if item.strip()]


def parse_extensions() -> set[str]:
    raw = os.getenv("EXTENSIONS", "").strip()
    if not raw:
        return DEFAULT_EXTENSIONS
    return {item.strip().lower() for item in raw.split(",") if item.strip()}


def require_api_key(x_api_key: str | None = Header(default=None), api_key: str | None = Query(default=None)) -> None:
    supplied = x_api_key or api_key
    if not supplied or supplied != get_api_key():
        raise HTTPException(status_code=401, detail="Invalid API key")


def iter_video_paths() -> Iterable[tuple[Path, Path]]:
    extensions = parse_extensions()
    for source in parse_sources():
        if not source.exists():
            continue
        for path in source.rglob("*"):
            if path.is_file() and path.suffix.lower() in extensions:
                yield source, path


def video_id(path: Path) -> str:
    return hashlib.sha256(str(path.resolve()).encode("utf-8")).hexdigest()[:24]


def replace_video_path_cache(paths: dict[str, Path]) -> None:
    global VIDEO_PATH_CACHE, VIDEO_PATH_CACHE_LOADED_AT
    with VIDEO_PATH_CACHE_LOCK:
        VIDEO_PATH_CACHE = paths
        VIDEO_PATH_CACHE_LOADED_AT = time.monotonic()


def refresh_video_path_cache() -> dict[str, Path]:
    paths: dict[str, Path] = {}
    for _, path in iter_video_paths():
        paths[video_id(path)] = path
    replace_video_path_cache(paths)
    return paths


def cached_video_path(video_file_id: str) -> Path | None:
    with VIDEO_PATH_CACHE_LOCK:
        if (
            VIDEO_PATH_CACHE_TTL_SECONDS > 0
            and time.monotonic() - VIDEO_PATH_CACHE_LOADED_AT > VIDEO_PATH_CACHE_TTL_SECONDS
        ):
            return None
        path = VIDEO_PATH_CACHE.get(video_file_id)
    if path is None:
        return None
    return path if path.exists() else None


def find_video_path_or_404(video_file_id: str) -> Path:
    path = cached_video_path(video_file_id)
    if path is None:
        entry = cached_library_entry(video_file_id)
        if entry is not None:
            path = Path(entry.path_text)
    if path is None:
        path = refresh_video_path_cache().get(video_file_id)
    if path is None or not path.exists():
        raise HTTPException(status_code=404, detail="Video not found")
    return path


def cache_key(path: Path, stat: os.stat_result) -> str:
    raw = f"{path.resolve()}|{stat.st_size}|{stat.st_mtime_ns}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def thumbnail_key(video: VideoFile) -> str:
    raw = f"{video.cache_key}|{THUMBNAIL_CACHE_VERSION}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def display_parts(source: Path, path: Path) -> tuple[str, str]:
    try:
        relative = path.relative_to(source)
    except ValueError:
        relative = Path(path.name)
    parts = relative.parts
    subfolder = str(Path(*parts[:-1])) if len(parts) > 1 else ""
    return path.name, subfolder


def normalize_path_text(path: Path) -> str:
    return str(path.resolve())


def read_deletion_flags(clean_missing: bool = False) -> set[str]:
    if not DELETION_FLAGS_FILE.exists():
        return set()

    try:
        lines = DELETION_FLAGS_FILE.read_text(encoding="utf-8").splitlines()
    except OSError:
        return set()

    flagged_paths: set[str] = set()
    changed = False
    for line in lines:
        path_text = line.strip()
        if not path_text:
            changed = True
            continue
        if path_text in flagged_paths:
            changed = True
            continue
        if clean_missing and not Path(path_text).exists():
            changed = True
            continue
        flagged_paths.add(path_text)

    if changed:
        write_deletion_flags(flagged_paths)
    return flagged_paths


def write_deletion_flags(flagged_paths: set[str]) -> None:
    DELETION_FLAGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    content = "".join(f"{path_text}\n" for path_text in sorted(flagged_paths, key=str.lower))
    DELETION_FLAGS_FILE.write_text(content, encoding="utf-8")


def metadata_path(key: str) -> Path:
    return METADATA_DIR / f"{key}.json"


def thumbnail_failure_path(key: str) -> Path:
    return THUMBNAIL_FAILURE_DIR / f"{key}.json"


def cached_thumbnail_failure(key: str) -> str | None:
    failure = thumbnail_failure_path(key)
    if not failure.exists():
        return None
    try:
        data = json.loads(failure.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return "previous thumbnail generation failed"
    reason = data.get("reason")
    return str(reason) if reason else "previous thumbnail generation failed"


def write_thumbnail_failure(key: str, video: VideoFile, reason: str) -> None:
    THUMBNAIL_FAILURE_DIR.mkdir(parents=True, exist_ok=True)
    data = {
        "reason": reason,
        "file_name": video.display_name,
        "path": str(video.path.resolve()),
        "failed_at": datetime.now(timezone.utc).isoformat(),
    }
    try:
        thumbnail_failure_path(key).write_text(json.dumps(data), encoding="utf-8")
    except OSError:
        pass


def clear_thumbnail_failure(key: str) -> None:
    try:
        thumbnail_failure_path(key).unlink()
    except FileNotFoundError:
        pass
    except OSError:
        pass


def get_duration_ms(path: Path, key: str) -> int:
    METADATA_DIR.mkdir(parents=True, exist_ok=True)
    cached = metadata_path(key)
    if cached.exists():
        try:
            data = json.loads(cached.read_text(encoding="utf-8"))
            return int(data.get("duration_ms", 0))
        except (OSError, ValueError, TypeError):
            pass

    duration_ms = probe_duration_ms(path)
    try:
        cached.write_text(json.dumps({"duration_ms": duration_ms}), encoding="utf-8")
    except OSError:
        pass
    return duration_ms


def probe_duration_ms(path: Path) -> int:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        return 0
    try:
        result = subprocess.run(
            [
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                str(path),
            ],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        return max(0, int(float(result.stdout.strip()) * 1000))
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return 0


def read_library_index_payload() -> tuple[dict[str, LibraryIndexEntry], float]:
    if not LIBRARY_INDEX_FILE.exists():
        return {}, 0.0
    try:
        data = json.loads(LIBRARY_INDEX_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}, 0.0

    entries: dict[str, LibraryIndexEntry] = {}
    for item in data.get("videos", []):
        if not isinstance(item, dict):
            continue
        try:
            entry = LibraryIndexEntry(
                id=str(item["id"]),
                cache_key=str(item["cache_key"]),
                title=str(item["title"]),
                display_name=str(item["display_name"]),
                subfolder=str(item.get("subfolder", "")),
                path_text=str(item["path"]),
                size=int(item["size"]),
                modified=float(item["modified"]),
                modified_ns=int(item["modified_ns"]),
                duration_ms=int(item.get("duration_ms", 0)),
            )
        except (KeyError, TypeError, ValueError):
            continue
        entries[entry.path_text] = entry

    try:
        scanned_at = float(data.get("scanned_at", 0.0))
    except (TypeError, ValueError):
        scanned_at = 0.0
    if scanned_at <= 0.0:
        try:
            scanned_at = LIBRARY_INDEX_FILE.stat().st_mtime
        except OSError:
            scanned_at = 0.0
    return entries, scanned_at


def write_library_index(entries: dict[str, LibraryIndexEntry], scanned_at: float | None = None) -> float:
    if scanned_at is None:
        scanned_at = time.time()
    LIBRARY_INDEX_FILE.parent.mkdir(parents=True, exist_ok=True)
    videos = [
        {
            "id": entry.id,
            "cache_key": entry.cache_key,
            "title": entry.title,
            "display_name": entry.display_name,
            "subfolder": entry.subfolder,
            "path": entry.path_text,
            "size": entry.size,
            "modified": entry.modified,
            "modified_ns": entry.modified_ns,
            "duration_ms": entry.duration_ms,
        }
        for entry in sorted(entries.values(), key=lambda item: item.display_name.lower())
    ]
    tmp = LIBRARY_INDEX_FILE.with_suffix(".tmp")
    tmp.write_text(
        json.dumps({"scanned_at": scanned_at, "videos": videos}, separators=(",", ":")),
        encoding="utf-8",
    )
    tmp.replace(LIBRARY_INDEX_FILE)
    return scanned_at


def library_paths_from_entries(entries: dict[str, LibraryIndexEntry]) -> dict[str, Path]:
    return {entry.id: Path(entry.path_text) for entry in entries.values()}


def update_library_snapshot(entries: dict[str, LibraryIndexEntry], scanned_at: float) -> None:
    global LIBRARY_SNAPSHOT_ENTRIES, LIBRARY_SNAPSHOT_SCANNED_AT, LIBRARY_SNAPSHOT_LOADED_AT
    snapshot_entries = dict(entries)
    with LIBRARY_SNAPSHOT_LOCK:
        LIBRARY_SNAPSHOT_ENTRIES = snapshot_entries
        LIBRARY_SNAPSHOT_SCANNED_AT = scanned_at
        LIBRARY_SNAPSHOT_LOADED_AT = time.monotonic()
    replace_video_path_cache(library_paths_from_entries(snapshot_entries))


def load_library_snapshot() -> dict[str, LibraryIndexEntry]:
    with LIBRARY_SNAPSHOT_LOCK:
        if LIBRARY_SNAPSHOT_ENTRIES is not None:
            return dict(LIBRARY_SNAPSHOT_ENTRIES)

    entries, scanned_at = read_library_index_payload()
    if entries or scanned_at > 0.0:
        update_library_snapshot(entries, scanned_at)
    return dict(entries)


def current_library_snapshot_scanned_at() -> float:
    with LIBRARY_SNAPSHOT_LOCK:
        return LIBRARY_SNAPSHOT_SCANNED_AT


def cached_library_entry(video_file_id: str) -> LibraryIndexEntry | None:
    for entry in load_library_snapshot().values():
        if entry.id == video_file_id:
            return entry
    return None


def videos_from_library_entries(entries: dict[str, LibraryIndexEntry], clean_missing_flags: bool = False) -> list[VideoFile]:
    with DELETION_FLAGS_LOCK:
        flags = read_deletion_flags(clean_missing=clean_missing_flags)
    videos = [entry_to_video(entry, flags) for entry in entries.values()]
    videos.sort(key=lambda item: item.display_name.lower())
    return videos


def cached_library_videos() -> list[VideoFile] | None:
    entries = load_library_snapshot()
    with LIBRARY_SNAPSHOT_LOCK:
        has_snapshot = LIBRARY_SNAPSHOT_ENTRIES is not None
    if not entries and not has_snapshot:
        return None
    return videos_from_library_entries(entries)


def library_refresh_is_running() -> bool:
    with LIBRARY_REFRESH_LOCK:
        return LIBRARY_REFRESH_THREAD is not None and LIBRARY_REFRESH_THREAD.is_alive()


def library_refresh_last_error() -> str:
    with LIBRARY_REFRESH_LOCK:
        return LIBRARY_REFRESH_LAST_ERROR


def library_cache_age_seconds() -> int | None:
    scanned_at = current_library_snapshot_scanned_at()
    if scanned_at <= 0.0:
        return None
    return max(0, int(time.time() - scanned_at))


def library_refresh_due(force: bool) -> bool:
    if force:
        return True
    if LIBRARY_REFRESH_INTERVAL_SECONDS <= 0:
        return False
    age = library_cache_age_seconds()
    return age is None or age >= LIBRARY_REFRESH_INTERVAL_SECONDS


def schedule_library_refresh(force: bool = False) -> bool:
    global LIBRARY_REFRESH_THREAD, LIBRARY_REFRESH_STARTED_AT
    if not library_refresh_due(force):
        return library_refresh_is_running()

    with LIBRARY_REFRESH_LOCK:
        if LIBRARY_REFRESH_THREAD is not None and LIBRARY_REFRESH_THREAD.is_alive():
            return True
        LIBRARY_REFRESH_STARTED_AT = time.time()
        LIBRARY_REFRESH_THREAD = threading.Thread(
            target=background_library_refresh,
            name="library-refresh",
            daemon=True,
        )
        LIBRARY_REFRESH_THREAD.start()
        return True


def wait_for_library_refresh(timeout_ms: int) -> None:
    if timeout_ms <= 0:
        return
    with LIBRARY_REFRESH_LOCK:
        thread = LIBRARY_REFRESH_THREAD
    if thread is not None and thread.is_alive():
        thread.join(timeout=max(0.0, timeout_ms / 1000.0))


def background_library_refresh() -> None:
    global LIBRARY_REFRESH_FINISHED_AT, LIBRARY_REFRESH_LAST_ERROR
    try:
        videos = scan_videos()
        if os.getenv("GENERATE_THUMBNAILS_BEFORE_LIST", "1") != "0":
            ensure_thumbnails_ready(videos)
        with LIBRARY_REFRESH_LOCK:
            LIBRARY_REFRESH_LAST_ERROR = ""
    except Exception as exc:  # Keep background refresh failures out of request handling.
        with LIBRARY_REFRESH_LOCK:
            LIBRARY_REFRESH_LAST_ERROR = str(exc)
    finally:
        with LIBRARY_REFRESH_LOCK:
            LIBRARY_REFRESH_FINISHED_AT = time.time()


def video_list_response(request: Request, videos: list[VideoFile], source: str) -> dict[str, object]:
    response: dict[str, object] = {
        "videos": [public_video(request, video) for video in videos],
        "source": source,
        "refreshing": library_refresh_is_running(),
        "cache_age_seconds": library_cache_age_seconds(),
        "refresh_interval_seconds": LIBRARY_REFRESH_INTERVAL_SECONDS,
    }
    error = library_refresh_last_error()
    if error:
        response["last_refresh_error"] = error
    return response


def modified_time_is_settled(modified: float) -> bool:
    if VIDEO_COPY_SETTLE_SECONDS <= 0:
        return True
    return time.time() - modified >= VIDEO_COPY_SETTLE_SECONDS


def video_is_settled(stat: os.stat_result) -> bool:
    return modified_time_is_settled(stat.st_mtime)


def video_path_is_settled(path: Path) -> bool:
    try:
        return video_is_settled(path.stat())
    except OSError:
        return False


def entry_to_video(entry: LibraryIndexEntry, flags: set[str]) -> VideoFile:
    path = Path(entry.path_text)
    return VideoFile(
        id=entry.id,
        cache_key=entry.cache_key,
        title=entry.title,
        display_name=entry.display_name,
        subfolder=entry.subfolder,
        path=path,
        size=entry.size,
        modified=entry.modified,
        duration_ms=entry.duration_ms,
        flagged_for_deletion=entry.path_text in flags,
    )


def build_library_index_entry(
    source: Path,
    path: Path,
    stat: os.stat_result,
    duration_ms: int,
) -> LibraryIndexEntry:
    key = cache_key(path, stat)
    display_name, subfolder = display_parts(source, path)
    return LibraryIndexEntry(
        id=video_id(path),
        cache_key=key,
        title=path.stem,
        display_name=display_name,
        subfolder=subfolder,
        path_text=normalize_path_text(path),
        size=stat.st_size,
        modified=stat.st_mtime,
        modified_ns=stat.st_mtime_ns,
        duration_ms=duration_ms,
    )


def scan_videos() -> list[VideoFile]:
    with DELETION_FLAGS_LOCK:
        flags = read_deletion_flags(clean_missing=True)
    with LIBRARY_INDEX_LOCK:
        cached_entries, _ = read_library_index_payload()
        next_entries: dict[str, LibraryIndexEntry] = {}
        videos: list[VideoFile] = []
        path_cache: dict[str, Path] = {}

        for source, path in iter_video_paths():
            try:
                stat = path.stat()
            except OSError:
                continue
            path_text = normalize_path_text(path)
            previous = cached_entries.get(path_text)
            if previous and previous.size == stat.st_size and previous.modified_ns == stat.st_mtime_ns:
                entry = previous
            else:
                if not video_is_settled(stat):
                    entry = build_library_index_entry(
                        source,
                        path,
                        stat,
                        previous.duration_ms if previous else 0,
                    )
                    if previous:
                        next_entries[path_text] = previous
                    path_cache[entry.id] = path
                    videos.append(entry_to_video(entry, flags))
                    continue
                entry = build_library_index_entry(source, path, stat, 0)
                entry = LibraryIndexEntry(
                    id=entry.id,
                    cache_key=entry.cache_key,
                    title=entry.title,
                    display_name=entry.display_name,
                    subfolder=entry.subfolder,
                    path_text=entry.path_text,
                    size=entry.size,
                    modified=entry.modified,
                    modified_ns=entry.modified_ns,
                    duration_ms=get_duration_ms(path, entry.cache_key),
                )

            next_entries[path_text] = entry
            path_cache[entry.id] = path
            videos.append(entry_to_video(entry, flags))

        videos.sort(key=lambda item: item.display_name.lower())
        replace_video_path_cache(path_cache)
        scanned_at = write_library_index(next_entries)
        update_library_snapshot(next_entries, scanned_at)
        return videos


def find_video_or_404(video_file_id: str) -> VideoFile:
    entry = cached_library_entry(video_file_id)
    if entry is not None:
        with DELETION_FLAGS_LOCK:
            flags = read_deletion_flags()
        video = entry_to_video(entry, flags)
        if video.path.exists():
            return video

    for video in scan_videos():
        if video.id == video_file_id:
            return video
    raise HTTPException(status_code=404, detail="Video not found")


def public_video(request: Request, video: VideoFile) -> dict[str, object]:
    api_key = get_api_key()
    stream_url = str(request.url_for("stream_video", video_file_id=video.id))
    thumbnail_url = str(request.url_for("cached_thumbnail", thumbnail_key=thumbnail_key(video)))
    return {
        "id": video.id,
        "title": video.title,
        "file_name": video.display_name,
        "subfolder": video.subfolder,
        "size": video.size,
        "duration_ms": video.duration_ms,
        "modified": datetime.fromtimestamp(video.modified, timezone.utc).isoformat(),
        "file_date": datetime.fromtimestamp(video.modified, timezone.utc).date().isoformat(),
        "flagged_for_deletion": video.flagged_for_deletion,
        "stream_url": f"{stream_url}?api_key={api_key}",
        "thumbnail_url": f"{thumbnail_url}?api_key={api_key}",
    }


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/videos")
def list_videos(
    request: Request,
    refresh: str = Query(default="background"),
    force: bool = Query(default=False),
    wait_ms: int = Query(default=0),
) -> dict[str, object]:
    require_api_key(request.headers.get("x-api-key"), request.query_params.get("api_key"))
    refresh_mode = refresh.strip().lower()
    cached_videos = cached_library_videos()

    if cached_videos is not None and refresh_mode != "wait":
        if refresh_mode != "cache":
            schedule_library_refresh(force=force)
            wait_for_library_refresh(wait_ms)
            if wait_ms > 0:
                cached_videos = cached_library_videos() or cached_videos
        return video_list_response(request, cached_videos, "cache")

    videos = scan_videos()
    if os.getenv("GENERATE_THUMBNAILS_BEFORE_LIST", "1") != "0":
        ensure_thumbnails_ready(videos)
    return video_list_response(request, videos, "scan")


@app.get("/videos/{video_file_id}/stream")
def stream_video(video_file_id: str, request: Request):
    require_api_key(request.headers.get("x-api-key"), request.query_params.get("api_key"))
    path = find_video_path_or_404(video_file_id)
    try:
        file_size = path.stat().st_size
    except OSError as exc:
        raise HTTPException(status_code=404, detail="Video not found") from exc
    range_header = request.headers.get("range")
    media_type = media_type_for(path)
    headers = {
        "Accept-Ranges": "bytes",
        "Cache-Control": "no-store",
    }

    if not range_header:
        headers["Content-Length"] = str(file_size)
        return StreamingResponse(
            iter_file_range(path, 0, file_size - 1),
            media_type=media_type,
            headers=headers,
        )

    byte_range = parse_range_header(range_header, file_size)
    if byte_range is None:
        raise HTTPException(
            status_code=416,
            detail="Requested Range Not Satisfiable",
            headers={"Content-Range": f"bytes */{file_size}", "Accept-Ranges": "bytes"},
        )

    start, end = byte_range
    content_length = end - start + 1
    headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    headers["Content-Length"] = str(content_length)
    return StreamingResponse(
        iter_file_range(path, start, end),
        status_code=206,
        media_type=media_type,
        headers=headers,
    )


def parse_range_header(range_header: str, file_size: int) -> tuple[int, int] | None:
    if file_size <= 0 or not range_header.startswith("bytes="):
        return None
    range_spec = range_header.removeprefix("bytes=").split(",", 1)[0].strip()
    if "-" not in range_spec:
        return None
    start_text, end_text = range_spec.split("-", 1)
    try:
        if start_text == "":
            suffix_length = int(end_text)
            if suffix_length <= 0:
                return None
            start = max(0, file_size - suffix_length)
            end = file_size - 1
        else:
            start = int(start_text)
            end = file_size - 1 if end_text == "" else int(end_text)
    except ValueError:
        return None
    if start < 0 or end < start or start >= file_size:
        return None
    return start, min(end, file_size - 1)


def iter_file_range(
    path: Path,
    start: int,
    end: int,
    chunk_size: int = STREAM_CHUNK_SIZE,
    initial_chunk_size: int = STREAM_INITIAL_CHUNK_SIZE,
):
    remaining = end - start + 1
    with open_streaming_read(path) as file:
        file.seek(start)
        first_chunk = True
        while remaining > 0:
            read_size = initial_chunk_size if first_chunk else chunk_size
            first_chunk = False
            chunk = file.read(min(read_size, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk


def open_streaming_read(path: Path):
    if os.name != "nt":
        return path.open("rb", buffering=0)

    handle = _KERNEL32.CreateFileW(
        str(path),
        _GENERIC_READ,
        _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE,
        None,
        _OPEN_EXISTING,
        _FILE_ATTRIBUTE_NORMAL,
        None,
    )
    if handle == _INVALID_HANDLE_VALUE:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, ctypes.FormatError(error_code), str(path))

    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY)
    except OSError:
        _KERNEL32.CloseHandle(handle)
        raise
    return os.fdopen(fd, "rb", buffering=0)


@app.get("/videos/{video_file_id}/thumbnail")
def video_thumbnail(video_file_id: str, request: Request) -> FileResponse:
    require_api_key(request.headers.get("x-api-key"), request.query_params.get("api_key"))
    video = find_video_or_404(video_file_id)
    try:
        thumbnail = ensure_thumbnail(video)
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=f"Thumbnail generation failed: {exc}") from exc
    response = FileResponse(thumbnail, media_type="image/jpeg")
    response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


@app.get("/thumbnails/{thumbnail_key}.jpg")
def cached_thumbnail(thumbnail_key: str, request: Request) -> FileResponse:
    require_api_key(request.headers.get("x-api-key"), request.query_params.get("api_key"))
    if not THUMBNAIL_KEY_PATTERN.fullmatch(thumbnail_key):
        raise HTTPException(status_code=404, detail="Thumbnail not found")

    thumbnail = THUMBNAIL_DIR / f"{thumbnail_key}.jpg"
    try:
        if not thumbnail.exists() or thumbnail.stat().st_size <= 2048:
            raise HTTPException(status_code=404, detail="Thumbnail not found")
    except OSError as exc:
        raise HTTPException(status_code=404, detail="Thumbnail not found") from exc

    response = FileResponse(thumbnail, media_type="image/jpeg")
    response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


@app.post("/videos/{video_file_id}/delete-flag")
def set_delete_flag(video_file_id: str, request: Request, flagged: bool = Query(default=True)) -> dict[str, object]:
    require_api_key(request.headers.get("x-api-key"), request.query_params.get("api_key"))
    video = find_video_or_404(video_file_id)
    write_deletion_flag(video.path, flagged)
    return {"id": video.id, "flagged_for_deletion": flagged}


def ensure_thumbnail(video: VideoFile) -> Path:
    if not video_path_is_settled(video.path):
        raise RuntimeError("video is still being copied or downloaded")
    stat = video.path.stat()
    video_key = cache_key(video.path, stat)
    key = hashlib.sha256(f"{video_key}|{THUMBNAIL_CACHE_VERSION}".encode("utf-8")).hexdigest()[:32]
    final_path = THUMBNAIL_DIR / f"{key}.jpg"
    if final_path.exists() and final_path.stat().st_size > 2048:
        return final_path
    failure_reason = cached_thumbnail_failure(key)
    if failure_reason:
        raise RuntimeError(f"previous thumbnail generation failed: {failure_reason}")

    with thumbnail_generation_lock(key):
        if final_path.exists() and final_path.stat().st_size > 2048:
            return final_path
        failure_reason = cached_thumbnail_failure(key)
        if failure_reason:
            raise RuntimeError(f"previous thumbnail generation failed: {failure_reason}")
        try:
            thumbnail = generate_thumbnail(video, key, final_path)
        except RuntimeError as exc:
            write_thumbnail_failure(key, video, str(exc))
            raise
        clear_thumbnail_failure(key)
        return thumbnail


def thumbnail_generation_lock(key: str) -> threading.Lock:
    with THUMBNAIL_LOCKS_LOCK:
        lock = THUMBNAIL_GENERATION_LOCKS.get(key)
        if lock is None:
            lock = threading.Lock()
            THUMBNAIL_GENERATION_LOCKS[key] = lock
        return lock


def thumbnail_is_ready(video: VideoFile) -> bool:
    try:
        final_path = THUMBNAIL_DIR / f"{thumbnail_key(video)}.jpg"
        return final_path.exists() and final_path.stat().st_size > 2048
    except OSError:
        return False


def ensure_thumbnails_ready(videos: list[VideoFile]) -> None:
    missing = [
        video
        for video in videos
        if modified_time_is_settled(video.modified) and not thumbnail_is_ready(video)
    ]
    if not missing:
        return

    for video in missing:
        try:
            ensure_thumbnail(video)
        except (OSError, RuntimeError):
            continue


def generate_thumbnail(video: VideoFile, key: str, final_path: Path) -> Path:
    THUMBNAIL_DIR.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("ffmpeg is not available")

    duration = max(1.0, video.duration_ms / 1000.0)
    candidates = [0.5, 0.35, 0.65, 0.2, 0.8, 0.1]
    best_path: Path | None = None
    best_size = -1
    for index, fraction in enumerate(candidates):
        candidate = THUMBNAIL_DIR / f"{key}.{index}.jpg"
        timestamp = min(max(duration * fraction, 1.0), max(duration - 1.0, 1.0))
        try:
            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-ss",
                    f"{timestamp:.3f}",
                    "-i",
                    str(video.path),
                    "-frames:v",
                    "1",
                    "-vf",
                    "crop=trunc(iw/4)*2:ih:0:0,scale=320:-2",
                    "-q:v",
                    "3",
                    str(candidate),
                ],
                capture_output=True,
                timeout=30,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        size = candidate.stat().st_size if candidate.exists() else 0
        if size > best_size:
            best_path = candidate
            best_size = size
        if size > 12_000:
            break

    if best_path and best_path.exists() and best_size > 2048:
        best_path.replace(final_path)
    else:
        raise RuntimeError("ffmpeg did not produce a usable thumbnail")

    for leftover in THUMBNAIL_DIR.glob(f"{key}.*.jpg"):
        try:
            leftover.unlink()
        except OSError:
            pass
    return final_path


def generate_thumbnails_on_startup() -> None:
    videos = scan_videos()
    total = len(videos)
    if total == 0:
        print("No videos found for thumbnail generation.", flush=True)
        return

    missing = [video for video in videos if not thumbnail_is_ready(video)]
    if not missing:
        print(f"All thumbnails ready for {total} videos.", flush=True)
        return

    print(f"Generating {len(missing)} missing thumbnails for {total} videos before server startup.", flush=True)
    completed = 0
    for video in missing:
        completed += 1
        try:
            ensure_thumbnail(video)
        except (OSError, RuntimeError) as exc:
            print(f"[{completed}/{len(missing)}] Failed thumbnail: {video.display_name}: {exc}", flush=True)
            continue
        print(f"[{completed}/{len(missing)}] Thumbnail ready: {video.display_name}", flush=True)


def write_deletion_flag(path: Path, flagged: bool) -> None:
    path_text = normalize_path_text(path)
    with DELETION_FLAGS_LOCK:
        flagged_paths = read_deletion_flags(clean_missing=True)
        if flagged:
            flagged_paths.add(path_text)
        else:
            flagged_paths.discard(path_text)
        write_deletion_flags(flagged_paths)


def media_type_for(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".mp4":
        return "video/mp4"
    if suffix == ".mkv":
        return "video/x-matroska"
    if suffix == ".mov":
        return "video/quicktime"
    return "application/octet-stream"


def ensure_dev_certificate(cert_file: Path, key_file: Path) -> None:
    if cert_file.exists() and key_file.exists():
        return

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key_file.parent.mkdir(parents=True, exist_ok=True)
    cert_file.parent.mkdir(parents=True, exist_ok=True)

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    hostname = socket.gethostname()
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COUNTRY_NAME, "US"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Air VR180 Dev"),
            x509.NameAttribute(NameOID.COMMON_NAME, hostname),
        ]
    )
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.now(timezone.utc) - timedelta(days=1))
        .not_valid_after(datetime.now(timezone.utc) + timedelta(days=3650))
        .add_extension(
            x509.SubjectAlternativeName(
                [
                    x509.DNSName(hostname),
                    x509.DNSName("localhost"),
                    x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                ]
            ),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    key_file.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the Air VR180 video server.")
    parser.add_argument(
        "--skip-thumbnails-on-startup",
        action="store_true",
        help="Start the server immediately and generate any missing thumbnails on the first /videos request.",
    )
    args = parser.parse_args()

    if not args.skip_thumbnails_on_startup:
        generate_thumbnails_on_startup()

    import uvicorn

    use_https = parse_bool_env("USE_HTTPS", False)
    uvicorn_options: dict[str, object] = {
        "host": os.getenv("HOST", "0.0.0.0"),
        "port": int(os.getenv("PORT", "8443")),
    }
    if use_https:
        cert_file = Path(os.getenv("CERT_FILE", "certs/dev-cert.pem"))
        key_file = Path(os.getenv("KEY_FILE", "certs/dev-key.pem"))
        ensure_dev_certificate(cert_file, key_file)
        uvicorn_options["ssl_certfile"] = str(cert_file)
        uvicorn_options["ssl_keyfile"] = str(key_file)
    uvicorn.run("main:app", **uvicorn_options)


if __name__ == "__main__":
    main()
