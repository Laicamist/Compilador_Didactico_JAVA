package edu.itssg.compiler.examples

/**
 * Biblioteca de ejemplos de clase (menú "Ejemplos" del espacio de trabajo).
 *
 * Son los mismos programas que valida `ClassExamplesTest` contra las láminas de la materia, así que lo que el
 * alumno ve al abrirlos es justo lo que las pruebas garantizan.
 *
 * @property unit Unidad de la materia cuya lámina reproduce (U1, U2 o U3).
 * @property expectsErrors `true` para los ejemplos hechos para llenar la Tabla de Errores.
 */
data class ClassExample(
    val id: String,
    val className: String,
    val fileName: String,
    val unit: String,
    val title: String,
    val description: String,
    val expectsErrors: Boolean,
    val source: String
)

object ClassExamples {

    val UNO = ClassExample(
        id = "uno",
        className = "Uno",
        fileName = "Uno.java",
        unit = "U2",
        title = "Clase Uno",
        description = "Cuádruplos, triplos y 3 direcciones de la lámina (atributos, método con parámetro y llamada desde main).",
        expectsErrors = false,
        source = """
        public class Uno{
        float dos = 3;
        float tres = dos+ 1;
        public void cuatro(int cinco){
        System.out.println( 45.0 + ( cinco * tres) );}
        public static void main(String []args){
        new Uno().cuatro('@');}}
        """.trimIndent()
    )

    val NUEVA = ClassExample(
        id = "nueva",
        className = "Nueva",
        fileName = "Nueva.java",
        unit = "U2",
        title = "Clase Nueva",
        description = "Atributo sin valor inicial (0.0f), división entera 15/4 y acceso a atributo desde main.",
        expectsErrors = false,
        source = """
        public class Nueva{
        float numero;
        double suma= 1+ numero;
        double total = suma+(15/4);
        public static void main(String [] c){
        System.out.println(new Nueva().total);}
        }
        """.trimIndent()
    )

    val A_NIVELES = ClassExample(
        id = "a_niveles",
        className = "A",
        fileName = "A.java",
        unit = "U2",
        title = "Clase A (26 renglones)",
        description = "Orden de ejecución por niveles, incremento ++a, cast (float) y \"hola\".length().",
        expectsErrors = false,
        source = """
        public class A{
        public int a= 35/4+10/3 ;
        int uno = ++a;
        private double dos= uno*(5-10);
        double tres= "hola".length()*30;
        public float cuatro=(float)( dos+tres*dos);
        public static void main(String [] q){
        System.out.println(new A().cuatro);}
        }
        """.trimIndent()
    )

    val LUNES = ClassExample(
        id = "lunes",
        className = "Lunes",
        fileName = "Lunes.java",
        unit = "U2",
        title = "Clase Lunes",
        description = "Tabla de símbolos con IDs repetidos por ámbito y resultado final 'R' de tipo char.",
        expectsErrors = false,
        source = """
        public class Lunes{
        int a= 'F';
        private char b= 99/10;
        int c= b+1;
        public char qwerty(int numero){
        String a= "1" ;
        return (char)(b + (a+c).length()+numero);}
        public static void main(String [] arreglo){
        System.out.println(new Lunes().qwerty(a));}}
        """.trimIndent()
    )

    val VIERNES = ClassExample(
        id = "viernes",
        className = "Viernes",
        fileName = "Viernes.java",
        unit = "U1",
        title = "Clase Viernes",
        description = "Tabla de símbolos con package, Math.random() (valor desconocido x) y parámetros.",
        expectsErrors = false,
        source = """
        package asesorias;
        public class Viernes{
        public double a (int x, String y){
        int variable = (int)(Math.random()*2);
        return x*variable+variable/3;}
        public static void main(String [] a){
        int num = '@'/10;
        System.out.println(new Viernes().a(num, "URIANGATO"));}}
        """.trimIndent()
    )

    val FACIL = ClassExample(
        id = "facil",
        className = "Facil",
        fileName = "Facil.java",
        unit = "U3",
        title = "Clase Facil",
        description = "Tabla de optimización: FO, SE, PCD y el resultado optimizado de 3 cuádruplos.",
        expectsErrors = false,
        source = """
        public class Facil{
        public double a(){
        float v = 33+2;
        double z = (99+10)/1;
        return z*3+v;}
        public static void main(String[]a){
        System.out.println(new Facil().a());}
        }
        """.trimIndent()
    )

    val FACILITO = ClassExample(
        id = "facilito",
        className = "Facilito",
        fileName = "Facilito.java",
        unit = "U1",
        title = "Errores: Facilito",
        description = "Tabla de errores léxicos (@, _), sintácticos (pakage) y semánticos (length sin paréntesis).",
        expectsErrors = true,
        source = """
        pakage lya;
        public class Facilito {
        float toma=10/3.0;
        int tu= 999@2 _
        float tiempo= tu/3-toma*70;
        public char operaciones (String deTrabajo)
        int 4a=deTrabajo.length+tiempo;
        return a; }
        }
        """.trimIndent()
    )

    val END_FIN = ClassExample(
        id = "end_fin",
        className = "End Fin",
        fileName = "EndFin.java",
        unit = "U1",
        title = "Errores: End Fin",
        description = "Errores sintácticos de las reglas CLASE, D_GLOB y DEC, y el ID inválido 9x.",
        expectsErrors = true,
        source = """
        package CODIGOS;
        public class End Fin {
        static int a ;
        float b=3.5;
        int private c=9;
        public static void main(int [] args) {
        int 9x=3;
        System.out.println(args[0]);
        double z=x*35/4 }
        Double a; }
        """.trimIndent()
    )

    val A_ERRORES = ClassExample(
        id = "a_errores",
        className = "A (errores)",
        fileName = "AErrores.java",
        unit = "U2",
        title = "Errores semánticos: A",
        description = "Uso de un ID no declarado y tipos incompatibles (String en char, double en float).",
        expectsErrors = true,
        source = """
        public class A{
        public int a;
        private double dos= uno*(5-10);
        char tres= "hola";
        public float cuatro= dos+(25+(10/(3- 1))+10*5);
        }
        """.trimIndent()
    )

    /** En el orden del menú: primero los que compilan sin errores (por unidad) y al final los de errores. */
    val all: List<ClassExample> = listOf(UNO, NUEVA, A_NIVELES, LUNES, VIERNES, FACIL, FACILITO, END_FIN, A_ERRORES)

    fun byId(id: String): ClassExample? = all.firstOrNull { it.id == id }
}
