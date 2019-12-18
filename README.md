# sample-cutter
Little commandline tool to cut recordings of a guitar/bass into samples

Previous things I tried before I wrote this tool:

* sox silence ...
  * the written sample starts at the frame that reaches the threshold value, but I lose the samples back to the initial zero crossing.
* dgedit
  * it would go back to the initial zero-crossing, but I failed to export the samples in a way that's usable by me.
