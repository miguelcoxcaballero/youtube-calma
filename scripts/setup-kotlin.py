"""Pinned compiler/runtime for the original Kotlin installer from Inhouse Photos."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import urllib.request
root=Path(__file__).resolve().parents[1]/'tools/kotlin';root.mkdir(parents=True,exist_ok=True)
artifacts={
'compiler.jar':'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar',
'stdlib.jar':'org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar',
'script-runtime.jar':'org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar',
'reflect.jar':'org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar',
'trove4j.jar':'org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar',
'annotations.jar':'org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar',
'coroutines.jar':'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar'}
def download(item):
 name,path=item;target=root/name
 if not target.exists():urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/'+path,target)
with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(download,artifacts.items()))
print('Kotlin compiler and runtime ready')
