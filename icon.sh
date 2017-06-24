#!/bin/sh

color="#1BE3C6"
icon=check-circle

font-awesome-svg-png --nopadding --sizes 192 --color "$color" --icons "$icon" --png --dest .
mv "$color/png/192/$icon.png" resources/public/img/icon.png
rm -rf "$color"
