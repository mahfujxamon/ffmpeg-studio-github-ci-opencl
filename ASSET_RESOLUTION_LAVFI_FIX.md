# AssetResolver fix — FFmpeg virtual lavfi inputs

The app's generic external-input resolver used to treat every relative `-i`
argument as a file that had to be imported into FFmpeg Assets.

That incorrectly rejected valid virtual FFmpeg inputs such as:

```text
-f lavfi -i color=c=red:s=320x240:d=2
```

The resolver now recognizes `-f lavfi -i ...` as a virtual input and leaves it
untouched. It also leaves stdin/pipe/fd/data/content/network inputs alone.
Normal relative media files still require import into FFmpeg Assets.
