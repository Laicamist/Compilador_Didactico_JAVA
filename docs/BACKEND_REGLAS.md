# Backend del compilador: reglas implementadas y su fuente

## 1. Análisis léxico (LstTokens, U1)

- Tokens exactamente como la tabla: `ID`, `TD`, `MA`, `CL`, `ODA`, `FI`, `VTE`, `VTD`, `VTF`, `VTC`, `VTS`, `PMAIN` (solo en `static void main`), etc.
- Limpia espacios y comentarios; un comentario `/*` sin cerrar es error léxico.
- Errores con la redacción de la U1: *"En Java no existe ningún TOKEN para el @, por lo tanto el símbolo no es válido para el lenguaje"*; `9x`, `4a` y `_` no cumplen con las características de un ID.
- Números válidos en Java que la tabla de tokens no contempla (`10L`, `5d`, `2.5d`, `1e3`, `0x1F`, `0b101`, `1_000`) tienen su propio mensaje: *"El literal 10L es un número válido en Java, pero no está contemplado en los tokens de la GLC v7 (solo VTE, VTD y VTF…)"*.

## 2. Tabla de símbolos (U1 "Elaborar tabla de símbolos", ejemplos `Viernes` y `Lunes`)

- Solo se numeran los elementos **creados por el programador** (paquete, clase, atributos, métodos, parámetros, variables, objetos), por orden de aparición: `ID1`, `ID2`...
- Los nombres de Java (`System`, `out`, `println`, `Math`, `random`, `length`) no se numeran.
- Un nombre que se declara de nuevo en otro ámbito recibe un ID nuevo: en `Viernes`, `ID3 a` (método) e `ID7 a` (parámetro de main); en `Lunes`, `ID7 a` (variable local de `qwerty`).
- En la tabla léxica, la primera aparición muestra el número y las siguientes se muestran como `IDn`.

## 3. Análisis sintáctico (GLC v7)

- Descendente recursivo con los nombres exactos de las reglas (`JAVA`, `DPACKAGE`, `CLASE`, `CONTENT`, `VARIOS`, `D_GLOB`, `MAIN`, `METODO`, `DEC`, `ASIG`, `OBJ`, `EXP`, `EXPC`, `ACC`, `CLA`, `OT`, `ARGS`...).
- Jerarquía de operadores de la U1/U2: nivel 1 `+ -`, nivel 2 `* /`, nivel 3 `.`, los paréntesis suben un nivel.
- Consideraciones de la GLC v7 verificadas: sin herencia/implementación, solo importación simple, sin arreglos, sin declaración en lista, sin variables ni métodos estáticos.
- **Modo pánico**: un error por instrucción; se sincroniza con `FI` (`;`) o `LLC` (`}`).
- Redacción de la U1: *"No cumple la regla CLASE, se esperaba un LLA y se encuentra un ID"* + *"Rompe la regla gramatical CLASE::=MA CL ID LLA CONTENT LLC"*.
- Erratas del documento interpretadas: `L_CASES::=PRCA L_CASES` → `CASE`; `STATMNT_B` → `STAT_B`; `EXPBOO` se analiza como `EXP` y el semántico exige boolean.
- Extensiones que exigen los ejemplos de la U2/U3 (la GLC no las deriva): `new Uno().cuatro('@')`, `("1"+c).length()`, conversiones `(char)(...)`, argumentos que son expresiones, `ACC FI` como instrucción, `SOUT`, `a++` y `-5`, cuerpos `{ }` en `if/while/do/for` (reglas moradas).
- Extensiones del 6 nov 2026 (documentadas en `GlcV7.kt`): `import` con varios niveles y comodín (`import java.util.Scanner;`, `import java.util.*;`) y `CLA ::= STR` para `String.valueOf(25)`.

## 4. Análisis semántico (U1 e Infografía)

- Declaración previa (incluye atributos usados antes de declararse), redeclaración en el mismo ámbito, variables sin inicializar.
- **Ámbitos de bloque (7 nov 2026):** cada `{ }` de `if`, `while`, `do`, `for` y `switch` abre un ámbito hijo. Lo declarado dentro no existe afuera (`for (int i…) { }` y luego `i` es error); bloques hermanos pueden repetir nombre (`if { int y; } else { int y; }`); no se permite ocultar una local de un bloque exterior (como en Java); los `case` comparten el bloque del `switch`.
- **Asignación definitiva (reglas de Java):** después de un `if/else` una variable queda inicializada solo si ambos brazos la inicializan; un `do-while` inicializa (su cuerpo se ejecuta al menos una vez); un `while`, un `for` o un `if` sin `else` no inicializan nada afuera.
- **Métodos básicos de Java** (`semantic/JavaApi.kt`): String (`length`, `charAt`, `substring`, `indexOf`, `equals`, `toUpperCase`…), Scanner (`nextLine`, `next`, `nextInt`, `nextDouble`…), Math (`random`, `abs`, `max`, `min`, `pow`, `sqrt`, `floor`, `ceil`, `round`, `PI`) y conversiones (`Integer.parseInt`, `Double.parseDouble`, `String.valueOf`…), con tipo de retorno, número de argumentos y método inexistente.
- Compatibilidad de TD con las reglas de Java (ampliación `int → float → double`, `char → int`, constantes que caben en `char/byte/short` como `char b = 99/10;`).
- Clase usada como valor (`Pruebas + a`), atributos y métodos inexistentes, *"El ID length no se encuentra como atributo en la clase String de Java"*, número y TD de argumentos, return faltante/incompatible, constructor por defecto con argumentos.
- Las fases son independientes: si una instrucción tiene un error sintáctico ajeno a su expresión (ej. `int 4a = deTrabajo.length;`), la expresión igual se valida.

## 5. Árbol abstracto-semántico (U1, reglas 1 a 7)

- Raíz `IDn PMAIN` conectada solo al método principal; acciones binarias; asignaciones, `SOUT`, `return` y métodos de Java no son binarios.
- Cada uso de un atributo, parámetro o local de un método se sustituye por su asignación (`ID2 =` con su valor).
- **Reasignaciones (corregido el 7 nov 2026):** `x = 5;` y `x++;` cambian el valor que usan las instrucciones siguientes, tanto en el main (se reutiliza el paso: `(ID3, =, 5, 5)` → `SOUT [2]`) como dentro de un método (locales, parámetros y atributos). Cada declaración conserva el valor de su momento: reasignar `a` después no cambia una `b` ya calculada con `a`.
- **Valores desconocidos con letras** (`x`, `y`, `z`…, como en la U3): lecturas de Scanner, `Math.random()`, métodos sobre valores desconocidos, operaciones no lineales y variables modificadas dentro de una estructura de control. Las operaciones lineales se arman con ellas: `2x+1`, `-y+3z+78`.
- **Orden de ejecución: de abajo hacia arriba y de izquierda a derecha, por niveles**, dentro de cada instrucción del main. Este número es el renglón del código intermedio.
  - Validado: `Uno` (8/8), `Nueva` (8/8), `A` de la U2 (26/26), ejemplos de PI, FO y RS de la U3.
  - Diferencias conocidas: en `Facil` (U3) la lámina intercambia los renglones 5 y 6; en `Lunes` y `Nueva` con texto la lámina sigue el orden de la notación prefija. Las láminas no son consistentes entre sí; se eligió la regla escrita en la U1.
- Las estructuras de control se validan, pero no generan árbol ni código intermedio (las láminas no definen su representación). Cada una deja una **observación** y las variables de afuera que modifica quedan con valor desconocido desde ahí: `int x = 1; if (x > 0) { x = 9; } println(x + 1);` → `(ID3, +, 1, x+1)`.

## 5.1 Observaciones (no son errores)

`CompilationReport.notes` (`core/error/CompilerNote.kt`) aclara lo que se muestra sin bloquear el código intermedio ni aparecer en la Tabla de Errores:

| Tipo | Cuándo |
|---|---|
| `CONTROL_FLOW_OMITTED` | Un `if`/`while`/`do`/`for`/`switch` no se representa; lista las variables que quedan desconocidas (`ID3 (x) = x`). |
| `DIVISION_BY_ZERO` | División o módulo de enteros entre cero (Java compila y lanza `ArithmeticException` al ejecutar; resultado `?`). Entre decimales el resultado es `Infinity`, como Java. |
| `CALL_DEPTH_LIMIT` | Las llamadas se anidan más de 16 niveles (recursión): el árbol se corta y el valor regresado queda `?`. |

## 6. Códigos intermedios (U2)

- Triplos `n (val1, ope, val2)` y Cuádruplos `n (val1, ope, val2, res)`, cierre `ID1 PMAIN -> [n]`.
- Valores como en las láminas: `'@'` = 64, `3.0f`, `301.0`, `TAB` (char 9), `'R'`, `"110"`, `X.0`, `15X.0`, `10+X.0` (`Math.random()`).
- Tres direcciones con 3 temporales: reutiliza temporales y registros de variables; `free T1` / `free all`; operaciones **pendientes** (`P3`) cuando faltan registros, como en el ejemplo `A`. Solo una expresión que por forma requiere más de 3 registros usa `T4`, y se indica.
- Cada instrucción de 3 direcciones guarda de qué paso sale (`sourceStep`), si es pendiente (`isPending`) y el estado de T1–T3 después de ejecutarse (`registersAfter`), para el recorrido animado del visor de árboles.
- Métodos de Java en el código intermedio: `([1], ., charAt(0), 'H')`, `(Math, ., pow(2,3), 8.0)`, Scanner en línea como en la lámina `(new Scanner, ., nextLine(), x)`.

## 7. Optimización (U3)

- Códigos oficiales: `SE`, `FO`, `PC`, `RS`, `CM`. La **D** se agrega cuando la técnica solo fue posible por un cambio previo en el registro: `SED`, `FOD`, `PCD`, `RSD`, `CMD`.
  - `Facil`: `FO 2`, `SE 3` (el `/1` ya estaba en el original), `PCD` (el `[2]` se volvió `109` por el cambio previo), `FO`.
  - PI: `PC` y luego `FOD`, como la lámina.
- Recorrido de arriba hacia abajo; el cuádruplo optimizado se elimina y su resultado pasa al "cuádruplo afectado". `Math.random()` nunca se reduce con RS.
- Por indicación del autor de las reglas, **el código de 3 direcciones no se optimiza**: la optimización aplica a triplos y cuádruplos.
- Tabla de la U3: Cuádruplo original | Optimización | Cuádruplo afectado | Cuádruplo modificado; RS se marca con el cuádruplo que repite (`RS[n]`) y los cuádruplos finales se pintan de verde en la app.
- Código muerto: lo que no impacta `SOUT`, `RTN` ni los accesos a la clase.

## 8. Seguridad

- Límites de profundidad (256 reglas, 32 paréntesis anidados, 256 operaciones encadenadas), 20,000 nodos en el árbol abstracto y 16 llamadas anidadas: el análisis no desborda la pila ni con hilos de 512 KB (iOS).
- Verificado con 4,000 entradas aleatorias o mutadas sin excepciones; expansión exponencial, recursión y ciclos entre atributos terminan en menos de 1 s.
- Cancelación: `compileAsync` revisa la cancelación entre cada fase (`checkpoint` de `compileSynchronously`), así que "Cancelar" detiene las fases que faltan.

## 8.1 Biblioteca de ejemplos

`examples/ClassExamples.kt`: los programas de las láminas (Uno, Nueva, A, Lunes, Viernes, Facil) y los de las tablas de errores (Facilito, End Fin, A con errores semánticos). Los usa el menú "Ejemplos" del espacio de trabajo y son las mismas cadenas que valida `ClassExamplesTest`.

## 9. Persistencia (SQLDelight)

- `CompilerDatabase.sq`: `CompilationSession` (título, código, fecha, con errores) con sus tablas de símbolos, errores y código intermedio (borrado en cascada), y `AppSetting` (clave/valor de ajustes). Migración `1.sqm` (versión 1 → 2) aplicada por las tres fábricas con `PRAGMA user_version`.
- `CompilerDatabaseProvider`: una sola conexión compartida por `CompilerSessionRepository` y `AppSettingsRepository`.

## Pruebas

`sharedLogic/src/commonTest/kotlin/edu/itssg/compiler/`:

| Archivo | Qué cubre |
|---|---|
| `ClassExamplesTest` | Ejemplos de las láminas U1–U3 renglón por renglón |
| `ClassExampleCatalogTest` | Biblioteca del menú "Ejemplos": cada programa compila como indica su ficha |
| `JavaMethodsTest` | String, Scanner, Math, conversiones, `import` y letras |
| `OptimizationAndRobustnessTest` | Técnicas de la U3, entradas corruptas, anti-DoS y cancelación entre fases |
| `ThreeAddressTraceTest` | Traza de temporales y pendientes de 3 direcciones |
| `SemanticRegressionTest` | Reasignaciones, ámbitos de bloque, asignación definitiva, observaciones y literales (7 nov 2026) |

Más `jvmTest/.../AppSettingsPersistenceJvmTest` (ajustes en SQLite). Ejecutar con `./gradlew :sharedLogic:allTests` (o `:sharedLogic:jvmTest` para solo Desktop).
