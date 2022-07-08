CREATE TABLE cara
(
    id            SERIAL UNIQUE NOT NULL PRIMARY KEY,
    email         TEXT          NOT NULL,
    respeito      INTEGER,
    reputacao     INTEGER,
    cargo         TEXT,
    nivel         INTEGER,
    golpes        INTEGER,
    golpesTomados INTEGER,
    votos         INTEGER,
    dataCriacao   TIMESTAMP     NOT NULL,
    ultimaEntrada TIMESTAMP     NOT NULL
);


CREATE TABLE partido
(
    id          SERIAL UNIQUE NOT NULL PRIMARY KEY,
    nome        TEXT          NOT NULL,
    dataCriacao TIMESTAMP     NOT NULL
);

CREATE TABLE filiacao
(
    id          SERIAL UNIQUE NOT NULL PRIMARY KEY,
    partido     INTEGER       NOT NULL references partido (id),
    cara        INTEGER       NOT NULL references cara (id),
    dataCriacao TIMESTAMP     NOT NULL
);

CREATE TABLE sessao
(
    id          UUID UNIQUE NOT NULL PRIMARY KEY,
    csrf        TEXT        NOT NULL,
    autenticada INTEGER references cara (id),
    dataCriacao TIMESTAMP   NOT NULL
);
