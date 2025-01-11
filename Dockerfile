FROM gitea.woggioni.net/woggioni/gbcs:memcached
COPY --chown=luser:luser conf/gbcs-memcached.xml /home/luser/.config/gbcs/gbcs.xml
