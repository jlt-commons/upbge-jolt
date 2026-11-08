"""Write the nREPL port to .nrepl-port so editors and agents autodiscover it.

Defaults to the project root (the parent of host/); override with
UPBGE_JOLT_PORT_FILE. The file is removed when Blender exits.
"""

import atexit
import os

DEFAULT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".nrepl-port")


def write(port):
    path = os.environ.get("UPBGE_JOLT_PORT_FILE", DEFAULT)
    with open(path, "w") as f:
        f.write(str(port))

    def cleanup():
        try:
            with open(path) as f:
                if f.read().strip() == str(port):
                    os.remove(path)
        except OSError:
            pass

    atexit.register(cleanup)
    return path
