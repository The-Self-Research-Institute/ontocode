# LGPL libraries in OntoCode

OntoCode itself is released under the MIT licence. It uses three libraries that are
licensed under the GNU Lesser General Public License (LGPL). They are used exactly as
published, without any change, and each one stays a separate jar file. Nothing is merged
or repackaged into OntoCode's own classes.

| Library | Maven coordinates | Version | Licence |
|---|---|---|---|
| HermiT reasoner | `net.sourceforge.owlapi:org.semanticweb.hermit` | 1.4.5.519 | LGPL-3.0 (some parts BSD-3-Clause) |
| JFact reasoner | `net.sourceforge.owlapi:jfact` | 5.0.3 | LGPL-2.1 |
| OWL Explanation | `net.sourceforge.owlapi:owlexplanation` | 5.0.0 | LGPL-3.0 |

Two small helper libraries travel with HermiT and are also LGPL: `net.sf.trove4j:trove4j`
3.0.3 and `net.sourceforge.owlapi:telemetry` 5.0.0.

The licence texts are in this folder: `LGPL-3.0.txt`, `LGPL-2.1.txt` and `GPL-3.0.txt`
(the LGPL-3.0 builds on the GPL-3.0).

## Where the jars are

- Spring Boot jars (editor, plugin service, reasoner worker, desktop backend): inside the
  jar at `BOOT-INF/lib/<file>.jar`, for example `BOOT-INF/lib/jfact-5.0.3.jar`.
- Desktop app (Electron): the five LGPL jars are NOT inside the backend jar. They are
  plain files in `resources/backend/lib/` (`jfact-5.0.3.jar`,
  `org.semanticweb.hermit-1.4.5.519.jar`, `owlexplanation-5.0.0.jar`, `telemetry-5.0.0.jar`,
  `trove4j-3.0.3.jar`). The backend jar is `resources/backend/jars/desktop.jar`. The licence
  files are installed at `resources/licenses/`.
- Docker images: the jars are inside `/app/app.jar` as above, and the licence files are in
  `/licenses/`.

## Where to get the source code

| Library | Upstream source |
|---|---|
| HermiT | https://github.com/phillord/hermit-reasoner (project site: http://www.hermit-reasoner.com ; it is also built from the OWL API project, https://github.com/owlcs/owlapi) |
| JFact | https://github.com/owlcs/jfact |
| OWL Explanation | https://github.com/matthewhorridge/owlexplanation |

Source jars for the exact versions we ship, from Maven Central:

- https://repo1.maven.org/maven2/net/sourceforge/owlapi/org.semanticweb.hermit/1.4.5.519/org.semanticweb.hermit-1.4.5.519-sources.jar
- https://repo1.maven.org/maven2/net/sourceforge/owlapi/jfact/5.0.3/jfact-5.0.3-sources.jar
- https://repo1.maven.org/maven2/net/sourceforge/owlapi/owlexplanation/5.0.0/owlexplanation-5.0.0-sources.jar

If you cannot reach these, contact the OntoCode maintainers and we will provide the source.

## How to use a different version of a library

You are free to replace any of these jars with your own build or another compatible
version. The replacement must keep the same public API.

### Spring Boot jars

A Spring Boot jar is a zip file. Its nested jars are listed in `BOOT-INF/classpath.idx`
(one line per jar, for example `- "BOOT-INF/lib/jfact-5.0.3.jar"`), and the loader reads
that list at start-up. The manifest (`META-INF/MANIFEST.MF`) does not list library files,
so it needs no change.

1. Unzip the jar into an empty folder: `unzip app.jar -d app`
2. Put your jar in `app/BOOT-INF/lib/`.
   - Easiest: give it the same file name as the one it replaces.
   - If the name differs, delete the old jar and edit the matching line in
     `app/BOOT-INF/classpath.idx` to the new file name.
3. Zip it again. The nested jars in `BOOT-INF/lib/` must be stored without compression,
   and `META-INF/MANIFEST.MF` should be the first entry:
   ```
   cd app
   zip -0 -r ../app-new.jar META-INF BOOT-INF/lib
   zip -r ../app-new.jar . -x 'META-INF/*' 'BOOT-INF/lib/*'
   ```
   Check that the file starts with the Spring Boot loader classes
   (`org/springframework/boot/loader/...`) and that `java -jar app-new.jar` starts.

### Desktop app

The desktop backend loads every jar in the `lib` folder next to its own jar. Nothing needs
to be unzipped or repacked.

1. Close OntoCode Studio.
2. Open the `resources/backend/lib` folder inside the app installation (the `resources`
   folder is next to the application executable, for example `<install dir>esources` on
   Windows).
3. Delete the jar you want to replace and put your jar in the same folder. Use a jar that
   contains the same library (same packages and classes). Its version must be compatible
   with OWL API 5.x, the version the editor is built against (5.5.0).
4. Start OntoCode Studio again. The backend picks up the new jar at start-up. The first
   start after a change rebuilds the start-up cache, so it can take a little longer.

If the lib folder is empty or a jar is missing, the backend will not start the reasoners
that need it.

### Docker images

Change the version of the library in the module's `pom.xml`
(`ontology-editor`, `ontology-plugin-service`, `ontology-reasoner-worker`) and rebuild the
image, for example `docker build -f Dockerfile.reasoner-worker -t ontocode-reasoner-worker .`
