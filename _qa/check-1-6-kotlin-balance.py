import pathlib
import re

files = [
    "app/src/main/java/app/walkquest/data/fog/FogGrid.kt",
    "app/src/main/java/app/walkquest/data/fog/FogRepository.kt",
    "app/src/main/java/app/walkquest/domain/OpenTileAtPoint.kt",
    "app/src/main/java/app/walkquest/viewmodel/MapViewModel.kt",
    "app/src/main/java/app/walkquest/ui/map/MapScreen.kt",
    "app/src/main/java/app/walkquest/di/AppContainer.kt",
    "app/src/main/java/app/walkquest/data/packages/PackageModels.kt",
    "app/src/test/kotlin/app/walkquest/data/fog/TestFog.kt",
    "app/src/test/kotlin/app/walkquest/data/fog/FogGridTest.kt",
    "app/src/test/kotlin/app/walkquest/data/fog/FogRepositoryTest.kt",
    "app/src/test/kotlin/app/walkquest/domain/OpenTileAtPointTest.kt",
]

for f in files:
    src = pathlib.Path(f).read_text(encoding="utf-8")
    src = re.sub(r'"""(.*?)"""', '""', src, flags=re.S)
    src = re.sub(r'"(?:[^"\\]|\\.)*"', '""', src)
    src = re.sub(r"'(?:[^'\\]|\\.)*'", "''", src)
    src = re.sub(r"//.*", "", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    for a, b in [("{", "}"), ("(", ")"), ("[", "]")]:
        assert src.count(a) == src.count(b), (f, a, src.count(a), src.count(b))
    print("OK", f)
