# --- sdl3deck: Easy Anti-Cheat offline start, for any bootstrapper name and any file-name case -------------------
# Easy Anti-Cheat can't run under ARM64 Proton: its bootstrapper (start_protected_game.exe, often renamed after the
# game) then stops with "Easy Anti-Cheat Launch Error: Failed to initialize Wine helper buffer". With "start without
# Easy Anti-Cheat" on (DROIDDECK_ANTICHEAT_OFFLINE, DroidDeck's default) the game's own executable from
# EasyAntiCheat/settings.json is started instead - but only for a bootstrapper named start_protected_game and an exact
# "EasyAntiCheat/settings.json". Games come from Windows, where names ignore case, and many rename the bootstrapper
# (to the game's name, the real game being e.g. "<name>_EAC.exe" or in Binaries/Win64). So here: the settings and the
# executable are found whatever their case, a bootstrapper is told by its contents (small, and it names
# EasyAntiCheat) when its name says nothing, and the settings' "parameters" are passed on as the bootstrapper does.
# Upstream's own rule runs first; this one only adds to it.
_upstream_offline_without_anticheat = offline_without_anticheat
EAC_BOOTSTRAPPER_MAX = 48 * 1024 * 1024
EAC_MARKS = (b"easyanticheat", "easyanticheat".encode("utf-16-le"))


def eac_find(folder, relative):
    """[folder]/[relative] matching each part without regard to case, as Windows does; None when missing."""
    path = folder
    for part in [p for p in relative.replace("\\", "/").split("/") if p and p != "."]:
        if part == "..":
            return None
        exact = os.path.join(path, part)
        if os.path.exists(exact):
            path = exact
            continue
        try:
            match = next((n for n in sorted(os.listdir(path)) if n.lower() == part.lower()), None)
        except OSError:
            return None
        if match is None:
            return None
        path = os.path.join(path, match)
    return path


def eac_bootstrapper(exe):
    """Whether [exe] is Easy Anti-Cheat's bootstrapper: its usual name, else small and naming EasyAntiCheat."""
    if re.fullmatch(r"start_protected_game(?:_?x64|_?64)?\.exe", os.path.basename(exe), re.IGNORECASE):
        return True
    try:
        if os.path.getsize(exe) > EAC_BOOTSTRAPPER_MAX:
            return False
        with open(exe, "rb") as source:
            if source.read(2) != b"MZ":
                return False
            tail = b""
            while True:
                chunk = source.read(4 << 20)
                if not chunk:
                    return False
                data = tail + chunk.lower()
                if any(mark in data for mark in EAC_MARKS):
                    return True
                tail = data[-32:]
    except OSError:
        return False


def eac_parameters(text):
    """The settings' "parameters": words, "double quoted" ones kept whole (Windows paths keep their backslashes)."""
    if not isinstance(text, str):
        return []
    return [a if a else b for a, b in re.findall(r'"([^"]*)"|(\S+)', text) if a or b]


def offline_without_anticheat(command, env):
    """Start the game's own executable instead of Easy Anti-Cheat's bootstrapper (opted in, offline only)."""
    started = _upstream_offline_without_anticheat(command, env)
    if started != command:
        return started
    try:
        if (env.get("DROIDDECK_ANTICHEAT_OFFLINE") != "1" or len(command) <= 2 or command[1] != "waitforexitandrun"
                or not command[2].lower().endswith(".exe") or not os.path.isfile(command[2])):
            return command
        folder = os.path.dirname(command[2])
        settings = eac_find(folder, "EasyAntiCheat/settings.json")
        if not settings or not os.path.isfile(settings):
            return command
        with open(settings, encoding="utf-8-sig") as source:
            config = json.load(source)
        executable = config.get("executable") if isinstance(config, dict) else None
        if not isinstance(executable, str) or not executable.strip() or os.path.isabs(executable.replace("\\", "/")):
            return command
        game = eac_find(folder, executable.strip())
        if not game or not os.path.isfile(game):
            return command
        root = os.path.realpath(folder)
        if os.path.commonpath((root, os.path.realpath(game))) != root:
            return command
        if os.path.realpath(game) == os.path.realpath(command[2]) or not eac_bootstrapper(command[2]):
            return command
        extra = eac_parameters(config.get("parameters"))
        print("droiddeck-game-env: started without Easy Anti-Cheat (offline only): %s instead of %s" % (
            os.path.relpath(game, folder), os.path.basename(command[2])), file=sys.stderr)
        return command[:2] + [game] + extra + command[3:]
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print("droiddeck-game-env: Easy Anti-Cheat left as is (%s)" % error, file=sys.stderr)
        return command
# --- end sdl3deck ---------------------------------------------------------------------------------------------------


