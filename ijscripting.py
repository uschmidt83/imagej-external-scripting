import numpy as np
import msgpack
import zmq
import tifffile
import os, tempfile, sys
from collections import namedtuple 
import types

type_map = {
    types.StringType: 'String',
    types.BooleanType: 'Boolean',
    types.IntType: 'Integer',
    types.FloatType: 'Double',
    np.float32: 'Float',
    np.float64: 'Double',
}

class ScriptingClient:
  def __init__(self, address="tcp://localhost:12345"):
    self.socket = None
    self.connect(address)

  def connect(self, address):
    self.disconnect()
    ctx = zmq.Context()
    self.socket = ctx.socket(zmq.REQ)
    self.socket.connect(address)

  def disconnect(self):
    if self.socket and self.connected():
      self.socket.close()

  def connected(self):
    return not self.socket.closed

  def run(self, script, script_name=None, outputs={}, headless=False, metadata={'axes': 'XYC'}, **kwargs):
    if not self.connected():
      raise RuntimeError("not connected, call connect()")

    try:
      args = ''
      params = '// #@ImageJ ij\n'
      code_in = ''
      code_out = ''
      files_in = {}
      files_out = {}
      for key, value in kwargs.items():
        # try:
        if type(value) is np.ndarray:
          files_in[key] = tempfile.mkstemp(prefix=key+"_", suffix=".tif")[1]
          tifffile.imsave(files_in[key], value, metadata=metadata, imagej=True)
          #tifffile.imsave(files_in[key], value)
        else:
          java_type = type_map[type(value)]
          params += '// #@%s %s\n' % (java_type, key)
          args += '%s="%s",' % (key,str(value))
        # except Exception:
        #    print "ignoring: can't handle parameter '%s' with %s" % (key, type(value))
        #    pass
      if args:
        args = args[:-1]
      if outputs:
        for key, value in outputs.items():
          if value is np.ndarray:
            files_out[key] = tempfile.mkstemp(prefix=key+"_", suffix=".tif")[1]
            #code_out += '\nij.io().save(%s, "%s")' % (key, files_out[key])
            code_out += '\nij.scifio().datasetIO().save(%s, "%s")' % (key, files_out[key])
          else:
            params += '// #@output %s %s\n' % (type_map[value], key)
      if files_in:
        code_in += "\n"
        for iname, fpath in files_in.items():
          #code_in += '%s = ij.io().open("%s")\n' % (iname, fpath)
          code_in += '%s = ij.scifio().datasetIO().open("%s")\n' % (iname, fpath)
              
      script_final = params + code_in + script + code_out + '\nnull'
      msg = {'name': script_name, 'code': script_final, 'args': args, 'headless': str(bool(headless))}
      # print script_final

      self.socket.send(msgpack.packb(msg))
      response = msgpack.unpackb(self.socket.recv())
      
      if '__exception__' in response:
        print 'ImageJ Exception: %s' % response['__exception__']
        print >> sys.stderr, response['stacktrace']
        return response
      
      # when no outputs: response == {'result': <last line of script>}

      
      # convert string response values to python types
      if outputs:
        for key in response.keys():
          if key not in outputs:
            del response[key]
          else:
            response[key] = outputs[key](response[key]) if response[key] != 'null' else None
      
      if files_out:
        for iname, fpath in files_out.items():
          response[iname] = tifffile.imread(fpath)

      # return response
      return namedtuple('ScriptOutput',response.keys())(*response.values())
    
    finally:
      map(os.remove,files_in.values())
      map(os.remove,files_out.values())