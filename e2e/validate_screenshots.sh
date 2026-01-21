#!/bin/bash
set -e

find workspaces -path "*/screenshots/actual_*.png" -type f | while read actual_file; do
    screenshots_dir=$(dirname "$actual_file")
    base_name=$(basename "$actual_file" | sed 's/^actual_//')
    reference_file="${screenshots_dir}/reference_${base_name}"
    
    if [ ! -f "$reference_file" ]; then
        echo "Error: Reference image not found for $actual_file"
        exit 1
    fi
    
    diff_file="${screenshots_dir}/diff_$(basename "$actual_file")"
    diff_output=$(compare -metric AE "$reference_file" "$actual_file" "$diff_file" 2>&1 || true)
    diff_pixels=$(echo "$diff_output" | grep -oE '^[0-9]+' || echo "0")
    
    if [ "$diff_pixels" = "0" ]; then
        echo "✓ Screenshots match"
        rm -f "$diff_file"
    else
        read width height <<< $(identify -format "%w %h" "$reference_file" 2>/dev/null || echo "0 0")
        total_pixels=$((width * height))
        
        if [ "$total_pixels" -gt 0 ]; then
            diff_percent=$(awk "BEGIN {printf \"%.2f\", ($diff_pixels * 100) / $total_pixels}")
            threshold=5.0
            
            if awk "BEGIN {exit !($diff_percent <= $threshold)}"; then
                echo "✓ Screenshots match (${diff_percent}% difference)"
                rm -f "$diff_file"
            else
                echo "✗ Screenshots differ (${diff_percent}% difference)"
                echo "  Difference image: $diff_file"
                exit 1
            fi
        else
            echo "✗ Screenshots differ"
            echo "  Difference image: $diff_file"
            exit 1
        fi
    fi
done

echo "All screenshot comparisons passed!"
