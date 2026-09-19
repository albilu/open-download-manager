"""ODM's filename policy, applied before both simulation and downloading."""
import hashlib
import json
import os

from yt_dlp.postprocessor.common import PostProcessor


class OdmOutputNamePP(PostProcessor):
    def __init__(self, downloader=None, configuration=None):
        super().__init__(downloader)
        with open(configuration, encoding='utf-8') as source:
            self.configuration = json.load(source)

    @staticmethod
    def shorten(value, budget=180):
        encoded = value.encode('utf-8')
        if len(encoded) <= budget:
            return value
        digest = hashlib.sha256(encoded).hexdigest()[:16]
        return encoded[:budget - 17].decode('utf-8', 'ignore') + '-' + digest

    def run(self, info):
        config = self.configuration
        def has_partial(filename):
            path = os.path.join(config['destination'], filename)
            return config['preserve_existing'] and any(
                os.path.lexists(path + ending) for ending in ('.part', '.aria2'))

        literal = config['literal']
        if literal:
            # Reserved literal names already include their uniquify suffix.
            # Preserve these across pause/resume, including older sessions.
            if (literal in config['reserved'] and len(literal.encode('utf-8')) <= 250) or has_partial(literal):
                info['odm_filename'] = literal
            else:
                stem, extension = os.path.splitext(literal)
                if len(extension.encode('utf-8')) > 32:
                    stem, extension = literal, ''
                info['odm_filename'] = self.shorten(stem) + extension
        else:
            stem = self._downloader.evaluate_outtmpl('%(title)s-%(id)s', info, True)
            suffix = '_' + str(config['counter']) if config['counter'] else ''
            original = stem + suffix
            # A previously reserved output owns its original name, even when
            # it predates this policy. Never redirect a restored partial file.
            reserved = any(name.startswith(original + '.') and len(name.encode('utf-8')) <= 250
                           for name in config['reserved'])
            existing = False
            if config['preserve_existing'] and len(stem.encode('utf-8')) > 180:
                # At pre_process the selected format/extension may not exist
                # yet. Match the original basename family, as reservations do.
                with os.scandir(config['destination'] or '.') as entries:
                    existing = any(entry.name.startswith(original + '.')
                                   and entry.name.endswith(('.part', '.aria2')) for entry in entries)
            info['odm_stem'] = original if reserved or existing else self.shorten(stem) + suffix
        return [], info
