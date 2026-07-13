import math
import zlib
import struct
import os

def write_png(buf, width, height):
    # Pack PNG header
    png = b'\x89PNG\r\n\x1a\n'
    # IHDR chunk
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    png += struct.pack('>I', 13) + b'IHDR' + ihdr + struct.pack('>I', zlib.crc32(b'IHDR' + ihdr))
    # IDAT chunk
    raw_data = b''
    for y in range(height):
        raw_data += b'\x00' # Filter type 0
        raw_data += buf[y * width * 4 : (y + 1) * width * 4]
    compressed = zlib.compress(raw_data, 9)
    png += struct.pack('>I', len(compressed)) + b'IDAT' + compressed + struct.pack('>I', zlib.crc32(b'IDAT' + compressed))
    # IEND chunk
    png += struct.pack('>I', 0) + b'IEND' + struct.pack('>I', zlib.crc32(b'IEND'))
    return png

def get_pixel(x, y, w, h):
    # Center coordinates to -1.0 to 1.0 range
    cx = (x - w/2) / (w/2)
    cy = (y - h/2) / (h/2)
    
    # Slant/skew the coordinates to make it italic (sporty)
    cx = cx - cy * 0.22
    
    # Model the S shape using two arcs and a joining bar
    r1 = 0.38
    d1 = math.sqrt(cx**2 + (cy + 0.32)**2)
    
    r2 = 0.38
    d2 = math.sqrt(cx**2 + (cy - 0.32)**2)
    
    dist_s = 999.0
    
    # Upper arc
    angle1 = math.atan2(cy + 0.32, cx)
    if angle1 > -math.pi * 0.6 or angle1 < math.pi * 0.8:
        dist_s = min(dist_s, abs(d1 - r1))
        
    # Lower arc
    angle2 = math.atan2(cy - 0.32, cx)
    if angle2 < math.pi * 0.6 or angle2 > -math.pi * 0.8:
        dist_s = min(dist_s, abs(d2 - r2))
        
    # Diagonal joining bar
    px = cx
    py = cy
    ax, ay = -0.32, -0.18
    bx, by = 0.32, 0.18
    l2 = (ax-bx)**2 + (ay-by)**2
    if l2 > 0:
        t = max(0, min(1, ((px-ax)*(bx-ax) + (py-ay)*(by-ay)) / l2))
        proj_x = ax + t * (bx - ax)
        proj_y = ay + t * (by - ay)
        dist_line = math.sqrt((px - proj_x)**2 + (py - proj_y)**2)
        if cy >= -0.25 and cy <= 0.25:
            dist_s = min(dist_s, dist_line)
        
    thickness = 0.14
    dist_to_shape = dist_s - thickness
    
    # Background color: #070b13 (R=7, G=11, B=19, A=255)
    bg_r, bg_g, bg_b, bg_a = 7, 11, 19, 255
    
    # Neon Cyan body color: #00E5FF (R=0, G=229, B=255)
    cyan_r, cyan_g, cyan_b = 0, 229, 255
    
    # Neon Pink/Red outline color: #FF007F (R=255, G=0, B=127)
    pink_r, pink_g, pink_b = 255, 0, 127
    
    if dist_to_shape < 0:
        # Inside the 'S' body
        border_width = 0.03
        if dist_to_shape > -border_width:
            t = (dist_to_shape + border_width) / border_width
            r = int(cyan_r * (1 - t) + pink_r * t)
            g = int(cyan_g * (1 - t) + pink_g * t)
            b = int(cyan_b * (1 - t) + pink_b * t)
        else:
            r, g, b = cyan_r, cyan_g, cyan_b
        return bytes([r, g, b, 255])
    else:
        # Outside 'S' body - draw neon glow
        glow_max_dist = 0.28
        if dist_to_shape < glow_max_dist:
            factor = (glow_max_dist - dist_to_shape) / glow_max_dist
            factor = factor ** 1.8 # smooth drop-off
            
            r = int(bg_r * (1 - factor) + pink_r * factor)
            g = int(bg_g * (1 - factor) + pink_g * factor)
            b = int(bg_b * (1 - factor) + pink_b * factor)
            return bytes([r, g, b, 255])
        else:
            return bytes([bg_r, bg_g, bg_b, bg_a])

def main():
    w, h = 128, 128
    buf = bytearray()
    for y in range(h):
        for x in range(w):
            buf.extend(get_pixel(x, y, w, h))
            
    png_data = write_png(buf, w, h)
    
    os.makedirs('express-server/public/web-app', exist_ok=True)
    
    with open('express-server/public/favicon.png', 'wb') as f:
        f.write(png_data)
    with open('express-server/public/web-app/favicon.png', 'wb') as f:
        f.write(png_data)
    # Also write favicon.ico as a copy of the png (modern browsers accept PNG as favicon.ico)
    with open('express-server/public/favicon.ico', 'wb') as f:
        f.write(png_data)
    with open('express-server/public/web-app/favicon.ico', 'wb') as f:
        f.write(png_data)
        
    print("PNG and ICO files generated successfully.")

if __name__ == '__main__':
    main()
