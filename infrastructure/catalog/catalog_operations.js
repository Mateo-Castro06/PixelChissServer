// Run only through update_catalog.py, which supplies the data and makes the backup.
const database = db.getSiblingDB(input.database);
const names = ["game_cards", "game_questions"];
const catalog = {game_cards: input.current.cards, game_questions: input.current.questions};
const retired = {game_cards: input.retired.cards, game_questions: input.retired.questions};

function snapshot() {
    const hashes = database.runCommand({dbHash: 1});
    if (hashes.ok !== 1) throw Error("No se pudieron verificar las huellas de MongoDB");
    const otherCollections = Object.fromEntries(Object.entries(hashes.collections)
        .filter(([name]) => !names.includes(name)).sort(([left], [right]) => left.localeCompare(right)));
    return {
        game_cards: database.game_cards.find().sort({_id:1}).toArray(),
        game_questions: database.game_questions.find().sort({_id:1}).toArray(),
        otherCollections
    };
}

function comparable(value) {
    if (Array.isArray(value)) return value.map(comparable);
    if (value && typeof value === "object") {
        return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
            .map(([key, child]) => [key, comparable(child)]));
    }
    return value;
}

function same(left, right) {
    return JSON.stringify(comparable(left)) === JSON.stringify(comparable(right));
}

function checkExisting(rows, name) {
    const definitions = new Map([...catalog[name], ...retired[name]].map(value => [value.id, value]));
    for (const document of rows) {
        const expected = definitions.get(document._id);
        if (!expected) throw Error("Documento no gestionado en " + name + "; no se modificó la base");
        const {id, ...fields} = expected;
        for (const [key, value] of Object.entries(fields)) {
            if (!same(document[key], value)) {
                throw Error("El documento " + id + " difiere del esperado en " + key + "; no se modificó la base");
            }
        }
    }
}

const before = snapshot();
for (const name of names) checkExisting(before[name], name);
if (!input.apply) {
    print(EJSON.stringify(before, {relaxed: false}));
    quit(0);
}

if (EJSON.stringify(before, {relaxed: false}) !== input.backup) {
    throw Error("El catálogo cambió después de la copia; no se modificó la base");
}
const options = {writeConcern: {w: 1, j: true}};
for (const name of names) {
    for (const {id, ...fields} of catalog[name]) {
        database.getCollection(name).updateOne({_id: id}, {$setOnInsert: fields}, {...options, upsert: true});
    }
}
for (const name of names) {
    const collection = database.getCollection(name);
    const activeIds = catalog[name].map(value => value.id);
    if (collection.countDocuments({_id: {$in: activeIds}}) !== activeIds.length) {
        throw Error("No se pudo comprobar la instalación; no se han retirado IDs antiguos");
    }
}
for (const name of names) {
    database.getCollection(name).deleteMany({_id: {$in: retired[name].map(value => value.id)}}, options);
}
const after = snapshot();
for (const name of names) {
    const ids = catalog[name].map(value => value.id).sort();
    if (!same(after[name].map(value => value._id).sort(), ids)) {
        throw Error("El conjunto final de " + name + " no coincide; revisar antes de iniciar Game");
    }
    checkExisting(after[name], name);
}
if (!same(before.otherCollections, after.otherCollections)) {
    throw Error("Cambió otra colección durante el mantenimiento; revisar antes de iniciar Game");
}
print(JSON.stringify({database: input.database, cards: after.game_cards.map(({_id, type}) => ({id: _id, type})),
    maps: Object.fromEntries(["classic","science"].map(map => [map, after.game_questions.filter(q => q.mapId === map).length])),
    questions: after.game_questions.length, otherCollectionsUnchanged: true}));
