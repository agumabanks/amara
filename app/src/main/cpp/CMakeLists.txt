cmake_minimum_required(VERSION 3.22.1)
project(sanaa_agent)

add_library(sanaa_agent SHARED message_parser.cpp action_scheduler.cpp)
find_library(log-lib log)
target_link_libraries(sanaa_agent ${log-lib})
