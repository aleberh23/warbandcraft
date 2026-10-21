#!/bin/bash
# Doble click en Finder: instala o actualiza Warband Craft en Prism Launcher.
cd "$(dirname "$0")/.." && ./mac/actualizar.sh "$@"
echo
read -n 1 -s -r -p "Apretá cualquier tecla para cerrar..."
