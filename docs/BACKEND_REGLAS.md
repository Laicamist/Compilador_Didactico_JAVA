# Backend del compilador: reglas implementadas y su fuente

## 1. Análisis léxico (LstTokens, U1)

- Tokens exactamente como la tabla: `ID`, `TD`, `MA`, `CL`, `ODA`, `FI`, `VTE`, `VTD`, `VTF`, `VTC`, `VTS`, `PMAIN` (solo en `static void main`), etc.
- Limpia espacios y comentarios; un comentario `/*` sin cerrar es error léxico.
- Errores con la redacción de la U1: *"En Java no existe ningún TOKEN para el @, por lo tanto el símbolo no es válido para el lenguaje"*; `9x`, `4a` y `_` no cumplen con las características de un ID.

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

## 4. Análisis semántico (U1 e Infografía)

- Declaración previa (incluye atributos usados antes de declararse), redeclaración en el mismo ámbito, variables sin inicializar.
- Compatibilidad de TD con las reglas de Java (ampliación `int → float → double`, `char → int`, constantes que caben en `char/byte/short` como `char b = 99/10;`).
- Clase usada como valor (`Pruebas + a`), atributos y métodos inexistentes, *"El ID length no se encuentra como atributo en la clase String de Java"*, número y TD de argumentos, return faltante/incompatible, constructor por defecto con argumentos.
- Las fases son independientes: si una instrucción tiene un error sintáctico ajeno a su expresión (ej. `int 4a = deTrabajo.length;`), la expresión igual se valida.

## 5. Árbol abstracto-semántico (U1, reglas 1 a 7)

- Raíz `IDn PMAIN` conectada solo al método principal; acciones binarias; asignaciones, `SOUT`, `return` y métodos de Java no son binarios.
- Cada uso de un atributo, parámetro o local de un método se sustituye por su asignación (`ID2 =` con su valor).
- **Orden de ejecución: de abajo hacia arriba y de izquierda a derecha, por niveles**, dentro de cada instrucción del main. Este número es el renglón del código intermedio.
  - Validado: `Uno` (8/8), `Nueva` (8/8), `A` de la U2 (26/26), ejemplos de PI, FO y RS de la U3.
  - Diferencias conocidas: en `Facil` (U3) la lámina intercambia los renglones 5 y 6; en `Lunes` y `Nueva` con texto la lámina sigue el orden de la notación prefija. Las láminas no son consistentes entre sí; se eligió la regla escrita en la U1.
- Las estructuras de control se validan, pero no generan árbol ni código intermedio (las láminas no definen su representación).

## 6. Códigos intermedios (U2)

- Triplos `n (val1, ope, val2)` y Cuádruplos `n (val1, ope, val2, res)`, cierre `ID1 PMAIN -> [n]`.
- Valores como en las láminas: `'@'` = 64, `3.0f`, `301.0`, `TAB` (char 9), `'R'`, `"110"`, `X.0`, `15X.0`, `10+X.0` (`Math.random()`).
- Tres direcciones con 3 temporales: reutiliza temporales y registros de variables; `free T1` / `free all`; operaciones **pendientes** (`P3`) cuando faltan registros, como en el ejemplo `A`. Solo una expresión que por forma requiere más de 3 registros usa `T4`, y se indica.

## 7. Optimización (U3)

- Códigos oficiales: `SE`, `FO`, `PC`, `RS`, `CM`. La **D** se agrega cuando la técnica solo fue posible por un cambio previo en el registro: `SED`, `FOD`, `PCD`, `RSD`, `CMD`.
  - `Facil`: `FO 2`, `SE 3` (el `/1` ya estaba en el original), `PCD` (el `[2]` se volvió `109` por el cambio previo), `FO`.
  - PI: `PC` y luego `FOD`, como la lámina.
- Recorrido de arriba hacia abajo; el cuádruplo optimizado se elimina y su resultado pasa al "cuádruplo afectado". `Math.random()` nunca se reduce con RS.
- Código muerto: lo que no impacta `SOUT`, `RTN` ni los accesos a la clase.

## 8. Seguridad

- Límites de profundidad (256 reglas, 32 paréntesis anidados, 256 operaciones encadenadas), 20,000 nodos en el árbol abstracto y 16 llamadas anidadas: el análisis no desborda la pila ni con hilos de 512 KB (iOS).
- Verificado con 4,000 entradas aleatorias o mutadas sin excepciones; expansión exponencial, recursión y ciclos entre atributos terminan en menos de 1 s.

## Pruebas

`sharedLogic/src/commonTest/kotlin/edu/itssg/compiler/`: `ClassExamplesTest` (ejemplos de las láminas) y `OptimizationAndRobustnessTest` (técnicas de la U3, entradas corruptas y anti-DoS). Ejecutar con `./gradlew :sharedLogic:jvmTest`.
