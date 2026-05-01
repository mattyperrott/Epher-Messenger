const assert = require('assert')
const binding = require('#binding')
const MachODylibCommand = require('./dylib-command')
const MachOLoadCommand = require('./load-command')
const MachORPathCommand = require('./rpath-command')

module.exports = class MachOBinary {
  constructor(opts = {}) {
    const { handle } = opts

    this._handle = handle
  }

  addSegmentCommand(command) {
    assert(this._handle)
    assert(command._handle)

    binding.machOBinaryAddSegmentCommand(this._handle, command._handle)
  }

  getLoadCommand(type) {
    assert(this._handle)
    assert.equal(typeof type, 'number')

    const handle = binding.machOBinaryGetLoadCommand(this, this._handle, type)

    if (handle === undefined) return null

    switch (type) {
      case MachOLoadCommand.TYPE.ID_DYLIB:
        return new MachODylibCommand({ handle })
      case MachOLoadCommand.TYPE.RPATH:
        return new MachORPathCommand({ handle })
      default:
        return new MachOLoadCommand({ handle })
    }
  }

  addLoadCommand(command) {
    assert(this._handle)
    assert(command._handle)

    const handle = binding.machOBinaryAddLoadCommand(this, this._handle, command._handle)

    if (handle === undefined) return null

    return new MachOLoadCommand({ handle })
  }

  hasLoadCommand(type) {
    assert(this._handle)
    assert.equal(typeof type, 'number')

    return binding.machOBinaryHasLoadCommand(this._handle, type)
  }

  removeLoadCommand(command) {
    assert(this._handle)
    assert(command._handle)

    return binding.machOBinaryRemoveLoadCommand(this._handle, command._handle)
  }

  removeAllLoadCommands(type) {
    assert(this._handle)
    assert.equal(typeof type, 'number')

    return binding.machOBinaryRemoveAllLoadCommands(this._handle, type)
  }

  addDylibCommand(command) {
    assert(this._handle)
    assert(command._handle)

    const handle = binding.machOBinaryAddDylibCommand(this, this._handle, command._handle)

    if (handle === undefined) return null

    return new MachODylibCommand({ handle })
  }

  findLibrary(name) {
    assert(this._handle)
    assert.equal(typeof name, 'string')

    const handle = binding.machOBinaryFindLibrary(this, this._handle, name)

    if (handle === undefined) return null

    return new MachODylibCommand({ handle })
  }

  addLibrary(name) {
    assert(this._handle)
    assert.equal(typeof name, 'string')

    binding.machOBinaryAddLibrary(this._handle, name)
  }

  [Symbol.for('bare.inspect')]() {
    return {
      __proto__: { constructor: MachOBinary }
    }
  }
}
