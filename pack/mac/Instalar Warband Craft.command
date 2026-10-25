#!/bin/bash
# Doble click en Mac: abre la Terminal y corre el instalador.
cd "$(dirname "$0")" || exit 1
if [ ! -f instalar.sh ]; then
    echo "Primero descomprimí el .zip entero en una carpeta y después abrí este archivo desde ahí."
    read -r -p "Apretá Enter para cerrar" _
    exit 1
fi
# Que macOS no vuelva a frenar los otros archivos del paquete
xattr -dr com.apple.quarantine . 2>/dev/null
exec /bin/bash ./instalar.sh "$@"
