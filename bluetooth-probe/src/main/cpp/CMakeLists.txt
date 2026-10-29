cmake_minimum_required(VERSION 3.10)
project(usb_bt_hci C)
add_library(usb_bt_hci SHARED usb_bt_hci.c)
target_compile_options(usb_bt_hci PRIVATE -O2 -Wall)

add_library(pioneer_blueforce SHARED pioneer_blueforce.c)
target_compile_options(pioneer_blueforce PRIVATE -O2 -Wall -Wextra -Werror)
target_link_libraries(pioneer_blueforce dl log)
